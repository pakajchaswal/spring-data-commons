/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.web.SortDefault.SortDefaults;
import org.springframework.hateoas.mvc.UriComponentsContributor;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@link HandlerMethodArgumentResolver} to automatically create {@link Sort} instances from request parameters or
 * {@link SortDefault} annotations.
 * 
 * @since 1.6
 * @author Oliver Gierke
 */
public class SortHandlerMethodArgumentResolver implements HandlerMethodArgumentResolver, UriComponentsContributor {

	private static final String DEFAULT_PARAMETER = "sort";
	private static final String DEFAULT_PROPERTY_DELIMITER = ",";
	private static final String DEFAULT_QUALIFIER_DELIMITER = "_";

	private static final String SORT_DEFAULTS_NAME = SortDefaults.class.getSimpleName();
	private static final String SORT_DEFAULT_NAME = SortDefault.class.getSimpleName();

	private String sortParameter = DEFAULT_PARAMETER;
	private String propertyDelimiter = DEFAULT_PROPERTY_DELIMITER;
	private String qualifierDelimiter = DEFAULT_QUALIFIER_DELIMITER;

	boolean legacyMode = false;

	/**
	 * Enables legacy mode parsing of the sorting parameter from the incoming request. Uses the sort property configured
	 * to lookup the fields to sort on and {@code $sortParameter.dir} for the direction.
	 * 
	 * @param legacyMode whether to enable the legacy mode or not.
	 */
	@Deprecated
	void setLegacyMode(boolean legacyMode) {
		this.legacyMode = legacyMode;
	}

	/**
	 * Configure the request parameter to lookup sort information from. Defaults to {@code sort}.
	 * 
	 * @param sortParameter must not be {@literal null} or empty.
	 */
	public void setSortParameter(String parameter) {

		Assert.hasText(parameter);
		this.sortParameter = parameter;
	}

	/**
	 * Configures the delimiter used to separate property references and the direction to be sorted by. Defaults to
	 * {@code}, which means sort values look like this: {@code firstname,lastname,asc}.
	 * 
	 * @param propertyDelimiter must not be {@literal null} or empty.
	 */
	public void setPropertyDelimiter(String propertyDelimiter) {

		Assert.hasText(propertyDelimiter, "Property delimiter must not be null or empty!");
		this.propertyDelimiter = propertyDelimiter;
	}

	/**
	 * Configures the delimiter used to separate the qualifier from the sort parameter. Defaults to {@code _}, so a
	 * qualified sort property would look like {@code qualifier_sort}.
	 * 
	 * @param qualifierDelimiter the qualifier delimiter to be used or {@literal null} to reset to the default.
	 */
	public void setQualifierDelimiter(String qualifierDelimiter) {
		this.qualifierDelimiter = qualifierDelimiter == null ? DEFAULT_QUALIFIER_DELIMITER : qualifierDelimiter;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.springframework.web.method.support.HandlerMethodArgumentResolver#supportsParameter(org.springframework.core.MethodParameter)
	 */
	public boolean supportsParameter(MethodParameter parameter) {
		return Sort.class.equals(parameter.getParameterType());
	}

	/* 
	 * (non-Javadoc)
	 * @see org.springframework.hateoas.mvc.UriComponentsContributor#enhance(org.springframework.web.util.UriComponentsBuilder, org.springframework.core.MethodParameter, java.lang.Object)
	 */
	public void enhance(UriComponentsBuilder builder, MethodParameter parameter, Object value) {

		if (!(value instanceof Sort)) {
			return;
		}

		Sort sort = (Sort) value;

		if (legacyMode) {

			List<String> expressions = legacyFoldExpressions(sort);
			Assert.isTrue(expressions.size() == 2,
					String.format("Expected 2 sort expressions (fields, direction) but got %d!", expressions.size()));
			builder.queryParam(getSortParameter(parameter), expressions.get(0));
			builder.queryParam(getLegacyDirectionParameter(parameter), expressions.get(1));

		} else {

			for (String expression : foldIntoExpressions(sort)) {
				builder.queryParam(getSortParameter(parameter), expression);
			}
		}
	}

	/* 
	 * (non-Javadoc)
	 * @see org.springframework.web.method.support.HandlerMethodArgumentResolver#resolveArgument(org.springframework.core.MethodParameter, org.springframework.web.method.support.ModelAndViewContainer, org.springframework.web.context.request.NativeWebRequest, org.springframework.web.bind.support.WebDataBinderFactory)
	 */
	public Sort resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {

		String[] directionParameter = webRequest.getParameterValues(getSortParameter(parameter));

		if (directionParameter != null && directionParameter.length != 0) {
			return legacyMode ? parseLegacyParameterIntoSort(webRequest, parameter) : parseParameterIntoSort(
					directionParameter, propertyDelimiter);
		} else {
			return getDefaults(parameter);
		}
	}

	/**
	 * Reads the default {@link Sort} to be used from the given {@link MethodParameter}. Rejects the parameter if both an
	 * {@link SortDefaults} and {@link SortDefault} annotation is found as we cannot build a reliable {@link Sort}
	 * instance then (property ordering).
	 * 
	 * @param parameter will never be {@literal null}.
	 * @return the default {@link Sort} instance derived from the parameter annotations or {@literal null}.
	 */
	private Sort getDefaults(MethodParameter parameter) {

		SortDefaults annotatedDefaults = parameter.getParameterAnnotation(SortDefaults.class);
		Sort sort = null;

		if (annotatedDefaults != null) {
			for (SortDefault annotatedDefault : annotatedDefaults.value()) {
				sort = appendOrCreateSortTo(annotatedDefault, sort);
			}
		}

		SortDefault annotatedDefault = parameter.getParameterAnnotation(SortDefault.class);

		if (annotatedDefault == null) {
			return sort;
		}

		if (sort != null && annotatedDefault != null) {
			throw new IllegalArgumentException(String.format(
					"Cannot use both @%s and @%s on parameter %s! Move %s into %s to define sorting order!", SORT_DEFAULTS_NAME,
					SORT_DEFAULT_NAME, parameter.toString(), SORT_DEFAULT_NAME, SORT_DEFAULTS_NAME));
		}

		return appendOrCreateSortTo(annotatedDefault, sort);
	}

	/**
	 * Creates a new {@link Sort} instance from the given {@link SortDefault} or appends it to the given {@link Sort}
	 * instance if it's not {@literal null}.
	 * 
	 * @param sortDefault
	 * @param sortOrNull
	 * @return
	 */
	private Sort appendOrCreateSortTo(SortDefault sortDefault, Sort sortOrNull) {

		String[] fields = SpringDataAnnotationUtils.getSpecificPropertyOrDefaultFromValue(sortDefault, "sort");

		if (fields.length == 0) {
			return null;
		}

		Sort sort = new Sort(sortDefault.direction(), fields);
		return sortOrNull == null ? sort : sortOrNull.and(sort);
	}

	/**
	 * Returns the sort parameter to be looked up from the request. Potentially applies qualifiers to it.
	 * 
	 * @param parameter will never be {@literal null}.
	 * @return
	 */
	private String getSortParameter(MethodParameter parameter) {

		StringBuilder builder = new StringBuilder();

		if (parameter.hasParameterAnnotation(Qualifier.class)) {
			builder.append(parameter.getParameterAnnotation(Qualifier.class).value()).append(qualifierDelimiter);
		}

		return builder.append(sortParameter).toString();
	}

	/**
	 * Creates a {@link Sort} instance from the given request expecting the {@link Direction} being encoded in a parameter
	 * with an appended {@code .dir}.
	 * 
	 * @param request must not be {@literal null}.
	 * @param parameter must not be {@literal null}.
	 * @return
	 */
	private Sort parseLegacyParameterIntoSort(WebRequest request, MethodParameter parameter) {

		String property = getSortParameter(parameter);
		String fields = request.getParameter(property);
		String directions = request.getParameter(getLegacyDirectionParameter(parameter));

		return new Sort(Direction.fromStringOrNull(directions), fields.split(","));
	}

	private String getLegacyDirectionParameter(MethodParameter parameter) {
		return getSortParameter(parameter) + ".dir";
	}

	/**
	 * Parses the given sort expressions into a {@link Sort} instance. The implementation expects the sources to be a
	 * concatenation of Strings using the given delimiter. If the last element can be parsed into a {@link Direction} it's
	 * considered a {@link Direction} and a simple property otherwise.
	 * 
	 * @param source will never be {@literal null}.
	 * @param delimiter the delimiter to be used to split up the source elements, will never be {@literal null}.
	 * @return
	 */
	Sort parseParameterIntoSort(String[] source, String delimiter) {

		List<Order> allOrders = new ArrayList<Sort.Order>();

		for (String part : source) {

			if (part == null) {
				continue;
			}

			String[] elements = part.split(delimiter);
			Direction direction = Direction.fromStringOrNull(elements[elements.length - 1]);

			for (int i = 0; i < elements.length; i++) {

				if (i == elements.length - 1 && direction != null) {
					continue;
				}

				allOrders.add(new Order(direction, elements[i]));
			}
		}

		return allOrders.isEmpty() ? null : new Sort(allOrders);
	}

	/**
	 * Folds the given {@link Sort} instance into a {@link List} of sort expressions, accumulating {@link Order} instances
	 * of the same direction into a single expression if they are in order.
	 * 
	 * @param sort must not be {@literal null}.
	 * @return
	 */
	private List<String> foldIntoExpressions(Sort sort) {

		List<String> expressions = new ArrayList<String>();
		ExpressionBuilder builder = null;

		for (Order order : sort) {

			Direction direction = order.getDirection();

			if (builder == null) {
				builder = new ExpressionBuilder(direction);
			} else if (!builder.hasSameDirectionAs(order)) {
				builder.dumpExpressionIfPresentInto(expressions);
				builder = new ExpressionBuilder(direction);
			}

			builder.add(order.getProperty());
		}

		return builder.dumpExpressionIfPresentInto(expressions);
	}

	/**
	 * Folds the given {@link Sort} instance into two expressions. The first being the property list, the second being the
	 * direction.
	 * 
	 * @throws IllegalArgumentException if a {@link Sort} with multiple {@link Direction}s has been handed in.
	 * @param sort must not be {@literal null}.
	 * @return
	 */
	private List<String> legacyFoldExpressions(Sort sort) {

		List<String> expressions = new ArrayList<String>();
		ExpressionBuilder builder = null;

		for (Order order : sort) {

			Direction direction = order.getDirection();

			if (builder == null) {
				builder = new ExpressionBuilder(direction);
			} else if (!builder.hasSameDirectionAs(order)) {
				throw new IllegalArgumentException(String.format(
						"%s in legacy configuration only supports a single direction to sort by!", getClass().getSimpleName()));
			}

			builder.add(order.getProperty());
		}

		return builder.dumpExpressionIfPresentInto(expressions);
	}

	/**
	 * Helper to easily build request parameter expressions for {@link Sort} instances.
	 * 
	 * @author Oliver Gierke
	 */
	class ExpressionBuilder {

		private final List<String> elements = new ArrayList<String>();
		private final Direction direction;

		/**
		 * Sets up a new {@link ExpressionBuilder} for properties to be sorted in the given {@link Direction}.
		 * 
		 * @param direction must not be {@literal null}.
		 */
		public ExpressionBuilder(Direction direction) {

			Assert.notNull(direction, "Direction must not be null!");
			this.direction = direction;
		}

		/**
		 * Returns whether the given {@link Order} has the same direction as the current {@link ExpressionBuilder}.
		 * 
		 * @param order must not be {@literal null}.
		 * @return
		 */
		public boolean hasSameDirectionAs(Order order) {
			return this.direction == order.getDirection();
		}

		/**
		 * Adds the given property to the expression to be built.
		 * 
		 * @param property
		 */
		public void add(String property) {
			this.elements.add(property);
		}

		/**
		 * Dumps the expression currently in build into the given {@link List} of {@link String}s. Will only dump it in case
		 * there are properties piled up currently.
		 * 
		 * @param expressions
		 * @return
		 */
		public List<String> dumpExpressionIfPresentInto(List<String> expressions) {

			if (elements.isEmpty()) {
				return expressions;
			}

			if (legacyMode) {
				expressions.add(StringUtils.collectionToDelimitedString(elements, propertyDelimiter));
				expressions.add(direction.name().toLowerCase());
			} else {
				elements.add(direction.name().toLowerCase());
				expressions.add(StringUtils.collectionToDelimitedString(elements, propertyDelimiter));
			}

			return expressions;
		}
	}
}
