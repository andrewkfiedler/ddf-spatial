/**
 * Copyright (c) Codice Foundation
 * 
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 * 
 **/
package org.codice.ddf.spatial.ogc.wfs.v2_0_0.catalog.source;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.bind.JAXBElement;

import net.opengis.fes._2.AbstractIdType;
import net.opengis.fes._2.BBOXType;
import net.opengis.fes._2.BinaryComparisonOpType;
import net.opengis.fes._2.BinaryLogicOpType;
import net.opengis.fes._2.BinarySpatialOpType;
import net.opengis.fes._2.ComparisonOpsType;
import net.opengis.fes._2.DistanceBufferType;
import net.opengis.fes._2.FilterType;
import net.opengis.fes._2.LiteralType;
import net.opengis.fes._2.LowerBoundaryType;
import net.opengis.fes._2.MeasureType;
import net.opengis.fes._2.ObjectFactory;
import net.opengis.fes._2.PropertyIsBetweenType;
import net.opengis.fes._2.PropertyIsLikeType;
import net.opengis.fes._2.ResourceIdType;
import net.opengis.fes._2.SpatialOpsType;
import net.opengis.fes._2.UnaryLogicOpType;
import net.opengis.fes._2.UpperBoundaryType;
import ogc.schema.opengis.gml.v_2_1_2.BoxType;
import ogc.schema.opengis.gml.v_2_1_2.CoordinatesType;
import ogc.schema.opengis.gml.v_2_1_2.LinearRingMemberType;
import ogc.schema.opengis.gml.v_2_1_2.LinearRingType;
import ogc.schema.opengis.gml.v_2_1_2.PointType;
import ogc.schema.opengis.gml.v_2_1_2.PolygonType;

import org.apache.commons.lang.StringUtils;
import org.apache.cxf.common.util.CollectionUtils;
import org.codice.ddf.spatial.ogc.wfs.catalog.common.FeatureAttributeDescriptor;
import org.codice.ddf.spatial.ogc.wfs.catalog.common.FeatureMetacardType;
import org.codice.ddf.spatial.ogc.wfs.v2_0_0.catalog.common.WfsConstants;
import org.codice.ddf.spatial.ogc.wfs.v2_0_0.catalog.common.WfsConstants.SPATIAL_OPERATORS;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;
import com.vividsolutions.jts.io.WKTWriter;

import ddf.catalog.data.Metacard;
import ddf.catalog.filter.FilterDelegate;

/**
 * The purpose of this class is to convert DDF OGC Filters into WFS compatible OGC Filters. This
 * class will return an "Invalid"(null) filter if a translation could not be made. It will return an
 * "Empty" filter, meaning no filters are set, only if it is a Content Type filter.
 * 
 */
public class WfsFilterDelegate extends FilterDelegate<FilterType> {

    private FeatureMetacardType featureMetacardType;

    private ObjectFactory filterObjectFactory = new ObjectFactory();

    private ogc.schema.opengis.gml.v_2_1_2.ObjectFactory gmlObjectFactory = new ogc.schema.opengis.gml.v_2_1_2.ObjectFactory();

    private static final Logger LOGGER = LoggerFactory.getLogger(WfsFilterDelegate.class);

    private static final String MISSING_PARAMETERS_MSG = "Required parameters are missing";

    private static final String PROPERTY_NOT_QUERYABLE = "'%s' is not a queryable property.";

    private List<String> supportedGeo;

    private String srsName;

    private boolean isEpsg4326 = false;

    public WfsFilterDelegate(FeatureMetacardType featureMetacardType, List<String> supportedGeo,
            String srsName) {

        if (featureMetacardType == null) {
            throw new IllegalArgumentException("FeatureMetacardType can not be null");
        }
        this.featureMetacardType = featureMetacardType;
        this.supportedGeo = supportedGeo;
        this.srsName = srsName;
        if (WfsConstants.EPSG_4326.equalsIgnoreCase(srsName)) {
            isEpsg4326 = true;
        } else {
            LOGGER.debug(
                    "Unable to convert geometry to {}. All geospatial queries for this featureType will be invalidated!",
                    srsName);
        }
    }

    public void setSupportedGeoFilters(List<String> supportedGeos) {
        LOGGER.debug("Updating supportedGeos to: {}", Arrays.toString(supportedGeos.toArray()));
        this.supportedGeo = supportedGeos;
    }

    private static enum PROPERTY_IS_OPS {
        PropertyIsEqualTo, PropertyIsLike, PropertyIsNotEqualTo, PropertyIsGreaterThan, PropertyIsGreaterThanOrEqualTo, PropertyIsLessThan, PropertyIsLessThanOrEqualTo;
    }

    @Override
    public FilterType and(List<FilterType> filtersToBeAnded) {
        return buildAndOrFilter(filtersToBeAnded,
                filterObjectFactory.createAnd(new BinaryLogicOpType()));
    }

    @Override
    public FilterType or(List<FilterType> filtersToBeOred) {
        // Remove invalid filters so they aren't OR'd.
        filtersToBeOred.removeAll(Collections.singleton(null));

        return buildAndOrFilter(filtersToBeOred,
                filterObjectFactory.createOr(new BinaryLogicOpType()));
    }

    @Override
    public FilterType not(FilterType filterToBeNoted) {
        FilterType returnFilter = new FilterType();
        if (filterToBeNoted == null) {
            return returnFilter;
        }
        UnaryLogicOpType notType = new UnaryLogicOpType();
        if (filterToBeNoted.isSetComparisonOps()) {
            notType.setComparisonOps(filterToBeNoted.getComparisonOps());
        } else if (filterToBeNoted.isSetLogicOps()) {
            notType.setLogicOps(filterToBeNoted.getLogicOps());
        } else if (filterToBeNoted.isSetSpatialOps()) {
            notType.setSpatialOps(filterToBeNoted.getSpatialOps());
        } else {
            return returnFilter;
        }
        returnFilter.setLogicOps(filterObjectFactory.createNot(notType));
        return returnFilter;
    }

    private Set<String> getFeatureIds(List<FilterType> filters) {
        Set<String> ids = new HashSet<String>();

        // This filter delegate requires that if one filter is a featureId
        // filter, they
        // must all be.
        if (!CollectionUtils.isEmpty(filters)) {
            boolean isFeatureIdFilter = filters.get(0) != null && filters.get(0).isSetId();

            for (FilterType filterType : filters) {

                if ((filterType != null && filterType.isSetId()) != isFeatureIdFilter) {
                    throw new UnsupportedOperationException(
                            "Query with mix of feature ID and non-feature ID queries not supported");
                }
                if (isFeatureIdFilter) {
                	
                	List<JAXBElement<? extends AbstractIdType>> idFilterTypeList = filterType.getId();
                	for (JAXBElement<? extends AbstractIdType> idFilter : idFilterTypeList) {
	                	
                		AbstractIdType absId = idFilter.getValue();
	                	
	                	ResourceIdType resId = (ResourceIdType)absId;
	                	ids.add(resId.getRid());
                	}
                }
            }

        }

        return ids;
    }

    private FilterType buildFeatureIdFilter(Set<String> ids) {
        FilterType filterType = new FilterType();

        for (String id : ids) {
        	List<JAXBElement<? extends AbstractIdType>> idFilterTypeList = filterType.getId();
        	ResourceIdType resId = new ResourceIdType();
        	resId.setRid(id);
        	idFilterTypeList.add(filterObjectFactory.createResourceId(resId));
        }
        return filterType;
    }

    private FilterType buildAndOrFilter(List<FilterType> filters,
            JAXBElement<BinaryLogicOpType> andOrFilter) {

        if (filters.isEmpty()) {
            return null;
        }
        removeEmptyFilters(filters);

        // Check if these filters contain featureID(s)
        Set<String> featureIds = getFeatureIds(filters);

        if (!CollectionUtils.isEmpty(featureIds)) {
            return buildFeatureIdFilter(featureIds);
        }

        // If we have 1 filter don't wrap it with AND/OR
        if (filters.size() == 1) {
            return filters.get(0);
        }

        for (FilterType filterType : filters) {
            // Determine which filterType is set
            if (filterType.isSetComparisonOps()) {
            	andOrFilter.getValue().getComparisonOpsOrSpatialOpsOrTemporalOps()
            		.add(filterType.getComparisonOps());
            } else if (filterType.isSetLogicOps()) {
            	andOrFilter.getValue().getComparisonOpsOrSpatialOpsOrTemporalOps()
            		.add(filterType.getLogicOps());
            } else if (filterType.isSetSpatialOps()) {
            	andOrFilter.getValue().getComparisonOpsOrSpatialOpsOrTemporalOps()
        			.add(filterType.getSpatialOps());
            }
        }
        FilterType returnFilter = new FilterType();
        returnFilter.setLogicOps(andOrFilter);

        return returnFilter;
    }

    private void removeEmptyFilters(List<FilterType> filters) {
        // Loop through the filters and remove any empty filters
        List<FilterType> filtersToBeRemoved = new ArrayList<FilterType>(filters.size());
        Boolean foundInvalidFilter = false;
        for (FilterType filterType : filters) {
            if (filterType == null) {
                foundInvalidFilter = true;
            } else if (!isFilterSet(filterType)) {
                filtersToBeRemoved.add(filterType);
            }

        }
        // If we found an invalid filter we want to return an invalid filter.
        if (foundInvalidFilter) {
            filters.clear();
            filters.add(null);
        } else {
            filters.removeAll(filtersToBeRemoved);
            filters.removeAll(Collections.singleton(null));
            if (filters.isEmpty()) {
                filters.add(new FilterType());
            }
        }
    }

    private Boolean isFilterSet(FilterType filter) {
        return (filter.isSetComparisonOps() || filter.isSetLogicOps() || filter.isSetSpatialOps() ||
        		filter.isSetId());
    }

    @Override
    public FilterType propertyIsEqualTo(String propertyName, String literal, boolean isCaseSensitive) {
        return buildPropertyIsFilterType(propertyName, literal, PROPERTY_IS_OPS.PropertyIsEqualTo);
    }

    @Override
    public FilterType propertyIsEqualTo(String propertyName, Date date) {
        return buildPropertyIsFilterType(propertyName, convertDateToIso8601Format(date),
                PROPERTY_IS_OPS.PropertyIsEqualTo);
    }

    @Override
    public FilterType propertyIsEqualTo(String propertyName, int literal) {
        return buildPropertyIsFilterType(propertyName, Integer.valueOf(literal),
                PROPERTY_IS_OPS.PropertyIsEqualTo);
    }

    @Override
    public FilterType propertyIsEqualTo(String propertyName, short literal) {
        return buildPropertyIsFilterType(propertyName, Short.valueOf(literal),
                PROPERTY_IS_OPS.PropertyIsEqualTo);
    }

    @Override
    public FilterType propertyIsEqualTo(String propertyName, long literal) {
        return buildPropertyIsFilterType(propertyName, Long.valueOf(literal),
                PROPERTY_IS_OPS.PropertyIsEqualTo);
    }

    @Override
    public FilterType propertyIsEqualTo(String propertyName, float literal) {
        return buildPropertyIsFilterType(propertyName, Float.valueOf(literal),
                PROPERTY_IS_OPS.PropertyIsEqualTo);
    }

    @Override
    public FilterType propertyIsEqualTo(String propertyName, double literal) {
        return buildPropertyIsFilterType(propertyName, Double.valueOf(literal),
                PROPERTY_IS_OPS.PropertyIsEqualTo);
    }

    @Override
    public FilterType propertyIsEqualTo(String propertyName, boolean literal) {
        return buildPropertyIsFilterType(propertyName, Boolean.valueOf(literal),
                PROPERTY_IS_OPS.PropertyIsEqualTo);
    }

    @Override
    public FilterType propertyIsLike(String propertyName, String literal, boolean isCaseSensitive) {
        return buildPropertyIsFilterType(propertyName, literal, PROPERTY_IS_OPS.PropertyIsLike);
    }

    @Override
    public FilterType propertyIsNotEqualTo(String propertyName, String literal,
            boolean isCaseSensitive) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsNotEqualTo);
    }

    @Override
    public FilterType propertyIsNotEqualTo(String propertyName, Date literal) {
        return buildPropertyIsFilterType(propertyName, convertDateToIso8601Format(literal),
                PROPERTY_IS_OPS.PropertyIsNotEqualTo);
    }

    @Override
    public FilterType propertyIsNotEqualTo(String propertyName, int literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsNotEqualTo);
    }

    @Override
    public FilterType propertyIsNotEqualTo(String propertyName, short literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsNotEqualTo);
    }

    @Override
    public FilterType propertyIsNotEqualTo(String propertyName, long literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsNotEqualTo);
    }

    @Override
    public FilterType propertyIsNotEqualTo(String propertyName, float literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsNotEqualTo);
    }

    @Override
    public FilterType propertyIsNotEqualTo(String propertyName, double literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsNotEqualTo);
    }

    @Override
    public FilterType propertyIsNotEqualTo(String propertyName, boolean literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsNotEqualTo);
    }

    @Override
    public FilterType propertyIsGreaterThan(String propertyName, String literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThan);
    }

    @Override
    public FilterType propertyIsGreaterThan(String propertyName, Date literal) {
        return buildPropertyIsFilterType(propertyName, convertDateToIso8601Format(literal),
                PROPERTY_IS_OPS.PropertyIsGreaterThan);
    }

    @Override
    public FilterType propertyIsGreaterThan(String propertyName, int literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThan);
    }

    @Override
    public FilterType propertyIsGreaterThan(String propertyName, short literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThan);
    }

    @Override
    public FilterType propertyIsGreaterThan(String propertyName, long literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThan);
    }

    @Override
    public FilterType propertyIsGreaterThan(String propertyName, float literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThan);
    }

    @Override
    public FilterType propertyIsGreaterThan(String propertyName, double literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThan);
    }

    @Override
    public FilterType propertyIsGreaterThanOrEqualTo(String propertyName, String literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsGreaterThanOrEqualTo(String propertyName, Date literal) {
        return buildPropertyIsFilterType(propertyName, convertDateToIso8601Format(literal),
                PROPERTY_IS_OPS.PropertyIsGreaterThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsGreaterThanOrEqualTo(String propertyName, int literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsGreaterThanOrEqualTo(String propertyName, short literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsGreaterThanOrEqualTo(String propertyName, long literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsGreaterThanOrEqualTo(String propertyName, float literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsGreaterThanOrEqualTo(String propertyName, double literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsGreaterThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsLessThan(String propertyName, String literal) {
        return buildPropertyIsFilterType(propertyName, literal, PROPERTY_IS_OPS.PropertyIsLessThan);
    }

    @Override
    public FilterType propertyIsLessThan(String propertyName, Date literal) {
        return buildPropertyIsFilterType(propertyName, convertDateToIso8601Format(literal),
                PROPERTY_IS_OPS.PropertyIsLessThan);
    }

    @Override
    public FilterType propertyIsLessThan(String propertyName, int literal) {
        return buildPropertyIsFilterType(propertyName, literal, PROPERTY_IS_OPS.PropertyIsLessThan);
    }

    @Override
    public FilterType propertyIsLessThan(String propertyName, short literal) {
        return buildPropertyIsFilterType(propertyName, literal, PROPERTY_IS_OPS.PropertyIsLessThan);
    }

    @Override
    public FilterType propertyIsLessThan(String propertyName, long literal) {
        return buildPropertyIsFilterType(propertyName, literal, PROPERTY_IS_OPS.PropertyIsLessThan);
    }

    @Override
    public FilterType propertyIsLessThan(String propertyName, float literal) {
        return buildPropertyIsFilterType(propertyName, literal, PROPERTY_IS_OPS.PropertyIsLessThan);
    }

    @Override
    public FilterType propertyIsLessThan(String propertyName, double literal) {
        return buildPropertyIsFilterType(propertyName, literal, PROPERTY_IS_OPS.PropertyIsLessThan);
    }

    @Override
    public FilterType propertyIsLessThanOrEqualTo(String propertyName, String literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsLessThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsLessThanOrEqualTo(String propertyName, Date literal) {
        return buildPropertyIsFilterType(propertyName, convertDateToIso8601Format(literal),
                PROPERTY_IS_OPS.PropertyIsLessThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsLessThanOrEqualTo(String propertyName, int literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsLessThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsLessThanOrEqualTo(String propertyName, short literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsLessThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsLessThanOrEqualTo(String propertyName, long literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsLessThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsLessThanOrEqualTo(String propertyName, float literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsLessThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsLessThanOrEqualTo(String propertyName, double literal) {
        return buildPropertyIsFilterType(propertyName, literal,
                PROPERTY_IS_OPS.PropertyIsLessThanOrEqualTo);
    }

    @Override
    public FilterType propertyIsBetween(String propertyName, String lowerBoundary,
            String upperBoundary) {
        return buildPropertyIsBetweenFilterType(propertyName, lowerBoundary, upperBoundary);
    }

    @Override
    public FilterType propertyIsBetween(String propertyName, Date lowerBoundary, Date upperBoundary) {
        return buildPropertyIsBetweenFilterType(propertyName,
                convertDateToIso8601Format(lowerBoundary),
                convertDateToIso8601Format(upperBoundary));
    }

    @Override
    public FilterType propertyIsBetween(String propertyName, int lowerBoundary, int upperBoundary) {
        return buildPropertyIsBetweenFilterType(propertyName, lowerBoundary, upperBoundary);
    }

    @Override
    public FilterType propertyIsBetween(String propertyName, short lowerBoundary,
            short upperBoundary) {
        return buildPropertyIsBetweenFilterType(propertyName, lowerBoundary, upperBoundary);
    }

    @Override
    public FilterType propertyIsBetween(String propertyName, long lowerBoundary, long upperBoundary) {
        return buildPropertyIsBetweenFilterType(propertyName, lowerBoundary, upperBoundary);
    }

    @Override
    public FilterType propertyIsBetween(String propertyName, float lowerBoundary,
            float upperBoundary) {
        return buildPropertyIsBetweenFilterType(propertyName, lowerBoundary, upperBoundary);
    }

    @Override
    public FilterType propertyIsBetween(String propertyName, double lowerBoundary,
            double upperBoundary) {
        return buildPropertyIsBetweenFilterType(propertyName, lowerBoundary, upperBoundary);
    }

    private FilterType buildPropertyIsBetweenFilterType(String propertyName, Object lowerBoundary,
            Object upperBoundary) {

        if (!isValidInputParameters(propertyName, lowerBoundary, upperBoundary)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }

        FilterType filter = new FilterType();

        if (featureMetacardType.getProperties().contains(propertyName)) {
            FeatureAttributeDescriptor featureAttributeDescriptor = (FeatureAttributeDescriptor) featureMetacardType
                    .getAttributeDescriptor(propertyName);
            if (featureAttributeDescriptor.isIndexed()) {
                filter.setComparisonOps(createPropertyIsBetween(
                        featureAttributeDescriptor.getPropertyName(), lowerBoundary, upperBoundary));
            } else {
                throw new IllegalArgumentException(String.format(PROPERTY_NOT_QUERYABLE,
                        propertyName));
            }
        } else {
            return null;
        }
        return filter;
    }

    private FilterType buildPropertyIsFilterType(String propertyName, Object literal,
            PROPERTY_IS_OPS propertyIsType) {

        if (!isValidInputParameters(propertyName, literal)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }
        FilterType returnFilter = new FilterType();
        // If this is a Content Type filter verify its for this Filter delegate.
        if (Metacard.CONTENT_TYPE.equals(propertyName)) {
            if (featureMetacardType.getName().equals(literal)) {
                return returnFilter;
            }
            return null;
        }
        // Special Case - If we get an ANY_TEXT we want to convert that to a
        // series of OR's
        if ((Metacard.ANY_TEXT.equalsIgnoreCase(propertyName))) {
            if (CollectionUtils.isEmpty(featureMetacardType.getTextualProperties())) {
                LOGGER.debug("Feature Type does not have Textual Properties to query.");
                return null;
            }

            if (featureMetacardType.getTextualProperties().size() == 1) {
                FeatureAttributeDescriptor attrDescriptor = (FeatureAttributeDescriptor) featureMetacardType
                        .getAttributeDescriptor(featureMetacardType.getTextualProperties().get(0));
                if (attrDescriptor.isIndexed()) {
                    returnFilter.setComparisonOps(createPropertyIsFilter(
                            attrDescriptor.getPropertyName(), literal, propertyIsType));
                } else {
                    LOGGER.debug("All textual properties have been blacklisted.  Removing from query.");
                    return null;
                }
            } else {
                List<FilterType> binaryCompOpsToBeOred = new ArrayList<FilterType>();
                for (String property : featureMetacardType.getTextualProperties()) {
                    // only build filters for queryable properties
                    FeatureAttributeDescriptor attrDesc = (FeatureAttributeDescriptor) featureMetacardType
                            .getAttributeDescriptor(property);
                    if (attrDesc.isIndexed()) {
                        FilterType filter = new FilterType();
                        filter.setComparisonOps(createPropertyIsFilter(attrDesc.getPropertyName(),
                                literal, propertyIsType));
                        binaryCompOpsToBeOred.add(filter);
                    } else {
                        LOGGER.debug(String.format(PROPERTY_NOT_QUERYABLE, property));
                    }
                }
                if (!binaryCompOpsToBeOred.isEmpty()) {
                    returnFilter = or(binaryCompOpsToBeOred);
                } else {
                    LOGGER.debug("All textual properties have been blacklisted.  Removing from query.");
                    return null;
                }
            }
            // filter is for a specific property; check to see if it is valid
        } else if (featureMetacardType.getProperties().contains(propertyName)) {
            FeatureAttributeDescriptor attrDesc = (FeatureAttributeDescriptor) featureMetacardType
                    .getAttributeDescriptor(propertyName);
            if (attrDesc.isIndexed()) {
                returnFilter.setComparisonOps(createPropertyIsFilter(attrDesc.getPropertyName(),
                        literal, propertyIsType));
            } else {
                // blacklisted property encountered
                throw new IllegalArgumentException(String.format(PROPERTY_NOT_QUERYABLE,
                        propertyName));
            }
        } else if (Metacard.ID.equals(propertyName)) {
            LOGGER.debug("feature id query for : {}", literal);
            String[] idTokens = literal.toString().split("\\.");
            if (idTokens.length > 1) {
                if (idTokens[0].equals(featureMetacardType.getName())) {
                    LOGGER.debug("feature type matches metacard type; creating featureID filter");
                    returnFilter.getId().add(createFeatureIdFilter(literal.toString()));
                } else {
                    LOGGER.debug("feature type does not match metacard type; invalidating filter");
                    return null;
                }
            } else {
                returnFilter.getId().add(createFeatureIdFilter(literal.toString()));
            }

        } else {
            return null;
        }
        return returnFilter;
    }

    private JAXBElement<? extends ComparisonOpsType> createPropertyIsFilter(String property,
            Object literal, PROPERTY_IS_OPS operation) {
        switch (operation) {
        case PropertyIsEqualTo:
            JAXBElement<BinaryComparisonOpType> propIsEqualTo = filterObjectFactory
                    .createPropertyIsEqualTo(new BinaryComparisonOpType());
            propIsEqualTo.getValue().getExpression().add(createPropertyNameType(property));
            propIsEqualTo.getValue().getExpression().add(createLiteralType(literal));
            
            return propIsEqualTo;

        case PropertyIsNotEqualTo:
            JAXBElement<BinaryComparisonOpType> propIsNotEqualTo = filterObjectFactory
                    .createPropertyIsNotEqualTo(new BinaryComparisonOpType());
            propIsNotEqualTo.getValue().getExpression().add(createPropertyNameType(property));
            propIsNotEqualTo.getValue().getExpression().add(createLiteralType(literal));

            return propIsNotEqualTo;

        case PropertyIsGreaterThan:
            JAXBElement<BinaryComparisonOpType> propIsGreaterThan = filterObjectFactory
                    .createPropertyIsGreaterThan(new BinaryComparisonOpType());
            propIsGreaterThan.getValue().getExpression().add(createPropertyNameType(property));
            propIsGreaterThan.getValue().getExpression().add(createLiteralType(literal));
           
            return propIsGreaterThan;

        case PropertyIsGreaterThanOrEqualTo:
            JAXBElement<BinaryComparisonOpType> propIsGreaterThanOrEqualTo = filterObjectFactory
                    .createPropertyIsGreaterThanOrEqualTo(new BinaryComparisonOpType());
            propIsGreaterThanOrEqualTo.getValue().getExpression()
                    .add(createPropertyNameType(property));
            propIsGreaterThanOrEqualTo.getValue().getExpression().add(createLiteralType(literal));

            return propIsGreaterThanOrEqualTo;

        case PropertyIsLessThan:
            JAXBElement<BinaryComparisonOpType> propIsLessThan = filterObjectFactory
                    .createPropertyIsLessThan(new BinaryComparisonOpType());
            propIsLessThan.getValue().getExpression().add(createPropertyNameType(property));
            propIsLessThan.getValue().getExpression().add(createLiteralType(literal));

            return propIsLessThan;

        case PropertyIsLessThanOrEqualTo:
            JAXBElement<BinaryComparisonOpType> propIsLessThanOrEqualTo = filterObjectFactory
                    .createPropertyIsLessThanOrEqualTo(new BinaryComparisonOpType());
            propIsLessThanOrEqualTo.getValue().getExpression()
                    .add(createPropertyNameType(property));
            propIsLessThanOrEqualTo.getValue().getExpression().add(createLiteralType(literal));

            return propIsLessThanOrEqualTo;

        case PropertyIsLike:
            JAXBElement<PropertyIsLikeType> propIsLike = filterObjectFactory
                    .createPropertyIsLike(new PropertyIsLikeType());
            //TODO: Figure out how to handle commented lines below
            //propIsLike.getValue()..setPropertyName(createPropertyNameType(property).getValue());
            propIsLike.getValue().setEscapeChar(WfsConstants.ESCAPE);
            propIsLike.getValue().setSingleChar(SINGLE_CHAR);
            propIsLike.getValue().setWildCard(WfsConstants.WILD_CARD);
           // propIsLike.getValue().setLiteral(createLiteralType(literal).getValue());

            return propIsLike;

        default:
            throw new UnsupportedOperationException("Unsupported Property Comparison Type");
        }
    }

    private JAXBElement<PropertyIsBetweenType> createPropertyIsBetween(String property,
            Object lowerBoundary, Object upperBoundary) {
        PropertyIsBetweenType propertyIsBetween = new PropertyIsBetweenType();
        propertyIsBetween.setLowerBoundary(createLowerBoundary(lowerBoundary));
        propertyIsBetween.setUpperBoundary(createUpperBoundary(upperBoundary));
        propertyIsBetween.setExpression(createPropertyNameType(property));

        return filterObjectFactory.createPropertyIsBetween(propertyIsBetween);
    }

    private JAXBElement<ResourceIdType> createFeatureIdFilter(final String id) {
    	ResourceIdType resId = new ResourceIdType();
    	resId.setRid(id);
    	ObjectFactory objFact = new ObjectFactory();
    	
    	return objFact.createResourceId(resId);
    }

    private boolean isValidInputParameters(String propertyName, Object literal) {
        if (literal == null || StringUtils.isEmpty(propertyName)
                || StringUtils.isEmpty(literal.toString())) {
            return false;
        }
        return true;

    }

    private boolean isValidInputParameters(String propertyName, String literal, double distance) {
        boolean isValid = isValidInputParameters(propertyName, literal);
        if (Double.valueOf(distance) < 0) {
            isValid = false;
        }
        return isValid;
    }

    private boolean isValidInputParameters(String propertyName, Object lowerBoundary,
            Object upperBoundary) {

        if (lowerBoundary == null || upperBoundary == null) {
            return false;
        }
        if (StringUtils.isEmpty(propertyName) || StringUtils.isEmpty(lowerBoundary.toString())
                || StringUtils.isEmpty(upperBoundary.toString())) {

            return false;
        }
        return true;
    }

    private DateTime convertDateToIso8601Format(Date inputDate) {
        return new DateTime(inputDate);
    }

    // spatial operators
    @Override
    public FilterType beyond(String propertyName, String wkt, double distance) {
        if (!isEpsg4326) {
            return null;
        }

        if (!isValidInputParameters(propertyName, wkt, distance)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }

        if (supportedGeo.contains(SPATIAL_OPERATORS.Beyond.getValue())) {
            return buildGeospatialFilterType(SPATIAL_OPERATORS.Beyond.toString(), propertyName,
                    wkt, distance);
        } else if (supportedGeo.contains(SPATIAL_OPERATORS.DWithin.getValue())) {
            return not(dwithin(propertyName, wkt, distance));
        } else {
            LOGGER.debug("WFS Source does not support Beyond filters");
            return null;
        }

    }

    @Override
    public FilterType contains(String propertyName, String wkt) {
        if (!isEpsg4326) {
            return null;
        }

        if (!isValidInputParameters(propertyName, wkt)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }

        if (supportedGeo.contains(SPATIAL_OPERATORS.Contains.getValue())) {
            return buildGeospatialFilterType(SPATIAL_OPERATORS.Contains.toString(), propertyName,
                    wkt, null);
        } else if (supportedGeo.contains(SPATIAL_OPERATORS.Within.getValue())) {
            return not(within(propertyName, wkt));
        } else {
            LOGGER.debug("WFS Source does not support Contains filters");
            return null;

        }

    }

    @Override
    public FilterType crosses(String propertyName, String wkt) {
        if (!isEpsg4326) {
            return null;
        }

        if (!isValidInputParameters(propertyName, wkt)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }

        if (supportedGeo.contains(SPATIAL_OPERATORS.Crosses.getValue())) {
            return buildGeospatialFilterType(SPATIAL_OPERATORS.Crosses.toString(), propertyName,
                    wkt, null);
        } else {
            LOGGER.debug("WFS Source does not support Crosses filters");
            return null;
        }

    }

    @Override
    public FilterType disjoint(String propertyName, String wkt) {
        if (!isEpsg4326) {
            return null;
        }

        if (!isValidInputParameters(propertyName, wkt)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }

        if (supportedGeo.contains(SPATIAL_OPERATORS.Disjoint.getValue())) {
            return buildGeospatialFilterType(SPATIAL_OPERATORS.Disjoint.toString(), propertyName,
                    wkt, null);
        } else if (supportedGeo.contains(SPATIAL_OPERATORS.BBOX.getValue())) {
            return not(bbox(propertyName, wkt));
        } else if (supportedGeo.contains(SPATIAL_OPERATORS.Intersects.getValue())) {
            return not(intersects(propertyName, wkt));
        } else {
            LOGGER.debug("WFS Source does not support Disjoint or BBOX filters");
            return null;
        }

    }

    @Override
    public FilterType dwithin(String propertyName, String wkt, double distance) {
        if (!isEpsg4326) {
            return null;
        }

        if (!isValidInputParameters(propertyName, wkt, distance)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }

        if (supportedGeo.contains(SPATIAL_OPERATORS.DWithin.getValue())) {
            return this.buildGeospatialFilterType(SPATIAL_OPERATORS.DWithin.toString(),
                    propertyName, wkt, distance);
        } else if (supportedGeo.contains(SPATIAL_OPERATORS.Beyond.getValue())) {
            return not(beyond(propertyName, wkt, distance));
        } else if (supportedGeo.contains(SPATIAL_OPERATORS.Intersects.getValue())) {
            String bufferedWkt = bufferGeometry(wkt, distance);
            return intersects(propertyName, bufferedWkt);
        } else {
            LOGGER.debug("WFS Source does not support the DWithin filter or any of its fallback filters (Not Beyond or Intersects).");
            return null;
        }
    }

    @Override
    public FilterType intersects(String propertyName, String wkt) {
        if (!isEpsg4326) {
            return null;
        }

        if (!isValidInputParameters(propertyName, wkt)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }

        if (supportedGeo.contains(SPATIAL_OPERATORS.Intersects.getValue())) {
            return buildGeospatialFilterType(SPATIAL_OPERATORS.Intersects.toString(), propertyName,
                    wkt, null);
        } else if (supportedGeo.contains(SPATIAL_OPERATORS.BBOX.getValue())) {
            return bbox(propertyName, wkt);
        } else if (supportedGeo.contains(SPATIAL_OPERATORS.Disjoint.getValue())) {
            return not(disjoint(propertyName, wkt));
        } else {
            LOGGER.debug("WFS Source does not support Intersect or BBOX");
            return null;
        }

    }

    @Override
    public FilterType overlaps(String propertyName, String wkt) {
        if (!isEpsg4326) {
            return null;
        }

        if (!isValidInputParameters(propertyName, wkt)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }

        if (supportedGeo.contains(SPATIAL_OPERATORS.Overlaps.getValue())) {
            return buildGeospatialFilterType(SPATIAL_OPERATORS.Overlaps.toString(), propertyName,
                    wkt, null);
        } else {
            LOGGER.debug("WFS Source does not support Overlaps filters");
            return null;
        }
    }

    @Override
    public FilterType touches(String propertyName, String wkt) {
        if (!isEpsg4326) {
            return null;
        }

        if (!isValidInputParameters(propertyName, wkt)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }

        if (supportedGeo.contains(SPATIAL_OPERATORS.Touches.getValue())) {
            return buildGeospatialFilterType(SPATIAL_OPERATORS.Touches.toString(), propertyName,
                    wkt, null);
        } else {
            LOGGER.debug("WFS Source does not support Beyond filters");
            return null;
        }
    }

    @Override
    public FilterType within(String propertyName, String wkt) {
        if (!isEpsg4326) {
            return null;
        }

        if (!isValidInputParameters(propertyName, wkt)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }

        if (supportedGeo.contains(SPATIAL_OPERATORS.Within.getValue())) {
            return buildGeospatialFilterType(SPATIAL_OPERATORS.Within.toString(), propertyName,
                    wkt, null);
        } else if (supportedGeo.contains(SPATIAL_OPERATORS.Contains.getValue())) {
            return not(within(propertyName, wkt));
        } else {
            LOGGER.debug("WFS Source does not support Within filters");
            return null;
        }
    }

    private FilterType bbox(String propertyName, String wkt) {
        if (!isEpsg4326) {
            return null;
        }

        if (!isValidInputParameters(propertyName, wkt)) {
            throw new IllegalArgumentException(MISSING_PARAMETERS_MSG);
        }

        if (supportedGeo.contains(SPATIAL_OPERATORS.BBOX.getValue())) {
            return buildGeospatialFilterType(SPATIAL_OPERATORS.BBOX.toString(), propertyName, wkt,
                    null);
        } else {
            LOGGER.debug("WFS Source does not support BBOX filters");
            return null;
        }

    }

    private FilterType buildGeospatialFilterType(String spatialOpType, String propertyName,
            String wkt, Double distance) {
        FilterType returnFilter = new FilterType();
        if (Metacard.ANY_GEO.equals(propertyName)) {

            if (CollectionUtils.isEmpty(featureMetacardType.getGmlProperties())) {
                LOGGER.debug("Feature Type does not have GEO properties to query");
                return null;
            }

            if (featureMetacardType.getGmlProperties().size() == 1) {
                FeatureAttributeDescriptor attrDesc = (FeatureAttributeDescriptor) featureMetacardType
                        .getAttributeDescriptor(featureMetacardType.getGmlProperties().get(0));
                if (attrDesc != null && attrDesc.isIndexed()) {
                    returnFilter.setSpatialOps(createSpatialOpType(spatialOpType,
                            attrDesc.getPropertyName(), wkt, distance));
                } else {
                    LOGGER.debug("All GEO properties have been blacklisted. Removing from query");
                    return null;
                }

            } else {
                List<FilterType> filtersToBeOred = new ArrayList<FilterType>();
                for (String property : featureMetacardType.getGmlProperties()) {
                    FeatureAttributeDescriptor attrDesc = (FeatureAttributeDescriptor) featureMetacardType
                            .getAttributeDescriptor(property);
                    if (attrDesc != null && attrDesc.isIndexed()) {
                        FilterType filter = new FilterType();
                        filter.setSpatialOps(createSpatialOpType(spatialOpType,
                                attrDesc.getPropertyName(), wkt, distance));
                        filtersToBeOred.add(filter);
                    } else {
                        LOGGER.debug(String.format(PROPERTY_NOT_QUERYABLE, property));
                    }
                }
                if (!filtersToBeOred.isEmpty()) {
                    returnFilter = or(filtersToBeOred);
                } else {
                    LOGGER.debug("All GEO properties have been blacklisted. Removing from query.");
                    returnFilter = null;
                }
            }
        } else if (featureMetacardType.getGmlProperties().contains(propertyName)) {
            FeatureAttributeDescriptor attrDesc = (FeatureAttributeDescriptor) featureMetacardType
                    .getAttributeDescriptor(propertyName);
            if (attrDesc != null && attrDesc.isIndexed()) {
                FilterType filter = new FilterType();
                filter.setSpatialOps(createSpatialOpType(spatialOpType, attrDesc.getPropertyName(),
                        wkt, distance));
                return filter;
            } else {
                // blacklisted property encountered
                throw new IllegalArgumentException(String.format(PROPERTY_NOT_QUERYABLE,
                        propertyName));
            }
        } else {
            return null;
        }
        return returnFilter;
    }

    private JAXBElement<? extends SpatialOpsType> createSpatialOpType(String operation,
            String propertyName, String wkt, Double distance) {

        switch (SPATIAL_OPERATORS.valueOf(operation)) {
        case BBOX:
            return buildBBoxType(propertyName, wkt);
        case Beyond:
            return buildDistanceBufferType(
                    filterObjectFactory.createBeyond(new DistanceBufferType()), propertyName, wkt,
                    distance);
        case Contains:
            return buildBinarySpatialOpType(
                    filterObjectFactory.createContains(new BinarySpatialOpType()), propertyName,
                    wkt);
        case Crosses:
            return buildBinarySpatialOpType(
                    filterObjectFactory.createCrosses(new BinarySpatialOpType()), propertyName, wkt);
        case Disjoint:
            return buildBinarySpatialOpType(
                    filterObjectFactory.createDisjoint(new BinarySpatialOpType()), propertyName,
                    wkt);
        case DWithin:
            return buildDistanceBufferType(
                    filterObjectFactory.createDWithin(new DistanceBufferType()), propertyName, wkt,
                    distance);
        case Intersects:
            return buildBinarySpatialOpType(
                    filterObjectFactory.createIntersects(new BinarySpatialOpType()), propertyName,
                    wkt);
        case Overlaps:
            return buildBinarySpatialOpType(
                    filterObjectFactory.createOverlaps(new BinarySpatialOpType()), propertyName,
                    wkt);
        case Touches:
            return buildBinarySpatialOpType(
                    filterObjectFactory.createTouches(new BinarySpatialOpType()), propertyName, wkt);
        case Within:
            return buildBinarySpatialOpType(
                    filterObjectFactory.createWithin(new BinarySpatialOpType()), propertyName, wkt);
        default:
            throw new UnsupportedOperationException("Unsupported geospatial filter type "
                    + SPATIAL_OPERATORS.valueOf(operation) + " specified");
        }

    }

    private JAXBElement<BinarySpatialOpType> buildBinarySpatialOpType(
            JAXBElement<BinarySpatialOpType> bsot, String propertyName, String wkt) {
        //TODO: Figure out how to handle commented lines below
        //bsot.getValue().setPropertyName(createPropertyNameType(propertyName).getValue());
        //bsot.getValue().setGeometry(createPolygon(wkt));

        return bsot;
    }

    private JAXBElement<DistanceBufferType> buildDistanceBufferType(
            JAXBElement<DistanceBufferType> dbt, String propertyName, String wkt, double distance) {
        MeasureType measureType = new MeasureType();
        measureType.setValue(distance);
        // the filter adapter normalizes all distances to meters
        measureType.setUom(WfsConstants.METERS);
        dbt.getValue().setDistance(measureType);
        //TODO: Figure out how to handle commented lines below
        //dbt.getValue().setGeometry(createPoint(wkt));
        //dbt.getValue().setPropertyName(createPropertyNameType(propertyName).getValue());

        return dbt;
    }

    private JAXBElement<BBOXType> buildBBoxType(String propertyName, String wkt) {
        BBOXType bboxType = new BBOXType();
        JAXBElement<BoxType> box = createBoxType(wkt);
        //TODO: Figure out how to handle commented lines below
        //bboxType.setBox(box.getValue());
        //bboxType.setPropertyName(createPropertyNameType(propertyName).getValue());

        return filterObjectFactory.createBBOX(bboxType);
    }

    private JAXBElement<BoxType> createBoxType(String wkt) {
        BoxType box = new BoxType();
        box.setSrsName(srsName);
        box.setCoordinates(createCoordinatesTypeFromWkt(wkt).getValue());
        return gmlObjectFactory.createBox(box);
    }

    private JAXBElement<PolygonType> createPolygon(String wkt) {
        PolygonType polygon = new PolygonType();
        LinearRingType linearRing = new LinearRingType();

        Coordinate[] coordinates = getCoordinatesFromWkt(wkt);
        if (coordinates != null && coordinates.length > 0) {
            StringBuffer coordString = new StringBuffer();

            for (Coordinate coordinate : coordinates) {
                coordString.append(coordinate.x).append(",").append(coordinate.y)
                        .append(" ");
            }

            CoordinatesType coordinatesType = new CoordinatesType();
            coordinatesType.setValue(coordString.toString());
            coordinatesType.setDecimal(".");
            coordinatesType.setCs(",");
            coordinatesType.setTs(" ");

            linearRing.setCoordinates(coordinatesType);
            LinearRingMemberType member = new LinearRingMemberType();
            member.setGeometry(gmlObjectFactory.createLinearRing(linearRing));
            polygon.setOuterBoundaryIs(member);
            polygon.setSrsName(srsName);

            return gmlObjectFactory.createPolygon(polygon);
        } else {
            throw new IllegalArgumentException(
                    "Unable to parse Polygon coordinates from WKT String");
        }

    }

    private JAXBElement<PointType> createPoint(String wkt) {
        Coordinate[] coordinates = getCoordinatesFromWkt(wkt);

        if (coordinates != null && coordinates.length > 0) {
            StringBuilder coordString = new StringBuilder();
            coordString.append(coordinates[0].x).append(",")
                    .append(coordinates[0].y);

            CoordinatesType coordinatesType = new CoordinatesType();
            coordinatesType.setValue(coordString.toString());

            PointType point = new PointType();
            point.setSrsName(srsName);
            point.setCoordinates(coordinatesType);

            return gmlObjectFactory.createPoint(point);
        } else {
            throw new IllegalArgumentException("Unable to parse Point coordinates from WKT String");
        }
    }

    private String buildCoordinateString(Envelope envelope) {
        StringBuilder sb = new StringBuilder();

        sb.append(envelope.getMinX()).append(",").append(envelope.getMinY())
                .append(" ").append(envelope.getMaxX()).append(",")
                .append(envelope.getMaxY());

        return sb.toString();
    }

    private JAXBElement<CoordinatesType> createCoordinatesTypeFromWkt(String wkt) {

        Envelope envelope = createEnvelopeFromWkt(wkt);

        String coords = buildCoordinateString(envelope);
        CoordinatesType coordinatesType = new CoordinatesType();

        coordinatesType.setValue(coords);

        return gmlObjectFactory.createCoordinates(coordinatesType);
    }

    private JAXBElement<LiteralType> createLiteralType(Object literalValue) {
        JAXBElement<LiteralType> literalType = filterObjectFactory.createLiteral(new LiteralType());
        literalType.getValue().getContent().add(literalValue.toString());
        return literalType;
    }

    private JAXBElement<String> createPropertyNameType(String propertyNameValue) {
        return filterObjectFactory.createValueReference(propertyNameValue);
    }

    private LowerBoundaryType createLowerBoundary(Object lowerBoundary) {
        LowerBoundaryType lowerBoundaryType = new LowerBoundaryType();
        lowerBoundaryType.setExpression(createLiteralType(lowerBoundary));
        return lowerBoundaryType;
    }

    private UpperBoundaryType createUpperBoundary(Object upperBoundary) {
        UpperBoundaryType upperBoundaryType = new UpperBoundaryType();
        upperBoundaryType.setExpression(createLiteralType(upperBoundary));
        return upperBoundaryType;
    }

    private Envelope createEnvelopeFromWkt(String wkt) {
        Envelope envelope = null;
        try {
            Geometry geo = getGeometryFromWkt(wkt);
            envelope = geo.getEnvelopeInternal();
        } catch (ParseException e) {
            throw new IllegalArgumentException("Unable to parse WKT String", e);
        }

        return envelope;

    }

    private Coordinate[] getCoordinatesFromWkt(String wkt) {
        Coordinate[] coordinates = null;
        try {
            Geometry geo = getGeometryFromWkt(wkt);
            coordinates = geo.getCoordinates();
        } catch (ParseException e) {
            throw new IllegalArgumentException("Unable to parse WKT String", e);
        }
        return coordinates;
    }

    private Geometry getGeometryFromWkt(String wkt) throws ParseException {
        return new WKTReader().read(wkt);
    }

    private String bufferGeometry(String wkt, double distance) {
        LOGGER.debug("Buffering WKT {} by distance {} meter(s).", wkt, distance);
        String bufferedWkt = null;
        try {
            Geometry geometry = getGeometryFromWkt(wkt);
            double bufferInDegrees = metersToDegrees(distance);
            LOGGER.debug("Buffering {} by {} degree(s).", geometry.getClass().getSimpleName(),
                    bufferInDegrees);
            Geometry bufferedGeometry = geometry.buffer(bufferInDegrees);
            bufferedWkt = new WKTWriter().write(bufferedGeometry);
            LOGGER.debug("Buffered WKT: {}.", bufferedWkt);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Unable to parse WKT String", e);
        }

        return bufferedWkt;
    }

    /**
     * This method approximates the degrees in latitude for the given distance (in meters) using the
     * formula for the meridian distance on Earth.
     * 
     * degrees = distance in meters/radius of Earth in meters * 180.0/pi
     * 
     * The approximate degrees in latitude can be used to compute a buffer around a given geometry
     * (see bufferGeometry()).
     */
    private double metersToDegrees(double distance) {
        double degrees = (distance / WfsConstants.EARTH_MEAN_RADIUS_METERS)
                * WfsConstants.RADIANS_TO_DEGREES;
        LOGGER.debug("{} meter(s) is approximately {} degree(s) of latitude.", distance, degrees);
        return degrees;
    }

}