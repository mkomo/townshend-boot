package com.mkomo.townshend.bean.helper.json;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.persistence.GeneratedValue;
import javax.persistence.ManyToMany;
import javax.persistence.OneToMany;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mkomo.townshend.bean.helper.EntitySchemaGenerator;

import lombok.Data;

@Data
@JsonInclude(Include.NON_EMPTY)
public class JsonSchema {

	public static final String SCHEMA_KEY = "@schema";

	//TODO allow collection of types
	private static final Logger logger = LoggerFactory.getLogger(EntitySchemaGenerator.class);

	private static final List<Class<? extends Annotation>> AUTOGENERATED_ANNOTATIONS =
			Arrays.asList(GeneratedValue.class, CreatedBy.class, LastModifiedBy.class, CreatedDate.class, LastModifiedDate.class);

	private static final List<String> AUTOGENERATED_FIELD_NAMES =
			Arrays.asList(SCHEMA_KEY);

	/**
	 * type is a validation, but we break it out so it can be handled separately
	 */
	private JsonSchemaType type;
	private Map<String, JsonSchema> properties = new LinkedHashMap<>();
	private Map<JsonSchemaValidation, Object> validations = new LinkedHashMap<>();
	/**
	 * schema is at a key other than the field name
	 */
	@JsonIgnore
	private String customKey = null;

	public static String schemaNameFromClass(Class<?> clazz) {
		return clazz.getSimpleName();
	}

	public static JsonSchema schemaFromClass(Class<?> cls) {
		return schemaFromClass(cls, Collections.emptySet());
	}

	public static JsonSchema schemaFromClass(Class<?> cls, Collection<Class<?>> classesToReference) {
		return schemaFromClass(cls, classesToReference, true);
	}

	public static JsonSchema schemaFromClass(Class<?> cls, Collection<Class<?>> classesToReference, boolean referenceRoot) {
		//create new arraylist so that we know the collection is mutable
		classesToReference = new ArrayList<>(classesToReference);
		JsonSchema schema = new JsonSchema();

		if (cls.isEnum()) {
			schema.type = JsonSchemaType.string;
			schema.validations.put(JsonSchemaValidation._enum, cls.getEnumConstants());
		} else if (cls.isPrimitive()) {
			if (cls.getName().equals("boolean")) {
				schema.type = JsonSchemaType._boolean;
			} else if (cls.getName().equals("char")) {
				schema.type = JsonSchemaType.string;
			} else if (cls.getName().equals("float") || cls.getName().equals("float")) {
				schema.type = JsonSchemaType.number;
			} else {
				schema.type = JsonSchemaType.integer;
			}
		} else if (Number.class.isAssignableFrom(cls)) {
			if (Long.class.isAssignableFrom(cls) ||
					Integer.class.isAssignableFrom(cls) ||
					Short.class.isAssignableFrom(cls) ||
					Byte.class.isAssignableFrom(cls) ||
					AtomicInteger.class.isAssignableFrom(cls) ||
					AtomicLong.class.isAssignableFrom(cls) ||
					BigInteger.class.isAssignableFrom(cls)) {
				schema.type = JsonSchemaType.integer;
			} else {
				schema.type = JsonSchemaType.number;
			}
		} else if (Boolean.class.isAssignableFrom(cls)) {
			schema.type = JsonSchemaType._boolean;
		} else if (String.class.isAssignableFrom(cls)) {
			schema.type = JsonSchemaType.string;
		} else if (Date.class.isAssignableFrom(cls)) {
			schema.type = JsonSchemaType.string;
			schema.validations.put(JsonSchemaValidation.format, "date-time");
		} else if (cls.isArray()) {
			schema.type = JsonSchemaType.array;
		} else if (Collection.class.isAssignableFrom(cls)) {
			schema.type = JsonSchemaType.array;
		} else {
			//cls is type object. we will describe the properties
			if (!referenceRoot || !classesToReference.contains(cls)) {
				classesToReference.add(cls);
				schema.type = JsonSchemaType.object;
				Class<?> sup = cls;
				while (sup != null) {
					for (Field f : sup.getDeclaredFields()) {
						if (JsonSchema.isEligibleForInclusion(f)) {
							JsonSchema s = JsonSchema.schemaFromField(f, classesToReference);
							if (s != null) {
								String name = s.getCustomKey() != null ? s.getCustomKey() : f.getName();
								if (AUTOGENERATED_FIELD_NAMES.contains(name)) {
									s.addValidation(JsonSchemaValidation.at_autogenerated, true);
								}
								schema.properties.put(name, s);
							}
						}
					}
					sup = sup.getSuperclass();
				}
			} else {
				//TODO add uri-template or other way to
				schema.validations.put(JsonSchemaValidation.at_schema, schemaNameFromClass(cls));
				schema.type = JsonSchemaType.object;
			}
		}
		return schema;
	}

	public static boolean isEligibleForInclusion(Field f) {
		return !Modifier.isStatic(f.getModifiers())
				&& !f.getName().startsWith("$$_");
	}

	public static JsonSchema schemaFromField(Field f, Collection<Class<?>> classesToReference) {
		if (f.getAnnotation(ManyToMany.class) != null || f.getAnnotation(OneToMany.class) != null) {
			//item is list
			//TODO handle if the type is map?
			//TODO check to make sure there is a ptype
			ParameterizedType pType = (ParameterizedType) f.getGenericType();
			Class<?> genericType = (Class<?>) pType.getActualTypeArguments()[0];
			JsonSchema schema = JsonSchema.schemaFromClass(genericType, classesToReference);
			JsonSchema outerSchema = new JsonSchema();
			outerSchema.type = JsonSchemaType.array;
			outerSchema.validations.put(JsonSchemaValidation.items, schema);
			schema = outerSchema;
			return schema;
		} else {
//			TODO handle annotations. for example, this could be autogenerated if there's an annotation for createdBy or updatedBy
			JsonSchema schema = JsonSchema.schemaFromClass(f.getType(), classesToReference);
			if (f.getAnnotations().length > 0) {
				List<String> annos = new ArrayList<>();
				for (Annotation a : f.getAnnotations()) {
					if (AUTOGENERATED_ANNOTATIONS.contains(a.annotationType())) {
						schema.addValidation(JsonSchemaValidation.at_autogenerated, true);
					}
					if (JsonProperty.class.equals(a.annotationType())) {
						JsonProperty anno = (JsonProperty) a;
						if (!anno.value().equals(JsonProperty.USE_DEFAULT_NAME)) {
							schema.setCustomKey(anno.value());
						}
					}
					annos.add(a.toString());
				}
				logger.debug("found annotations that are not being added to schema: {}", annos);
				schema.addValidation(JsonSchemaValidation.debug, annos);
			}
			return schema;
		}
	}

	@JsonAnyGetter
	public Map<JsonSchemaValidation, Object> getValidations() {
		return this.validations;
	}

	@JsonAnySetter
	public void addValidation(JsonSchemaValidation key, Object value) {
		this.validations.put(key, value);
	}
}