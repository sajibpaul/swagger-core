package com.wordnik.swagger.jackson;

import com.wordnik.swagger.util.Json;

import com.wordnik.swagger.annotations.ApiModel;
import com.wordnik.swagger.converter.ModelConverter;
import com.wordnik.swagger.converter.ModelConverterContext;
import com.wordnik.swagger.models.*;
import com.wordnik.swagger.models.properties.*;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.annotation.*;
import java.lang.reflect.Type;
import java.util.*;

public class ModelResolver extends AbstractModelConverter implements ModelConverter {
	Logger LOGGER = LoggerFactory.getLogger(ModelResolver.class);

  @SuppressWarnings("serial")
  public ModelResolver(ObjectMapper mapper) {
	  super(mapper);
  }

  public ObjectMapper objectMapper() {
    return _mapper;
  }

  public Property resolveProperty(Type type, ModelConverterContext context) {
    return resolveProperty(_mapper.constructType(type),context);
  }

  public Property resolveProperty(JavaType propType, ModelConverterContext context) {
    Property property = null;
    String typeName = _typeName(propType);

    // primitive or null
    property = getPrimitiveProperty(typeName);

    // modelProp.setQualifiedType(_typeQName(propType));
    // And then properties specific to subset of property types:
    if (propType.isEnumType()) {
      // _addEnumProps(propDef, propType.getRawClass(), modelProp);
    } else if (propType.isContainerType()) {
      JavaType keyType = propType.getKeyType();
      JavaType valueType = propType.getContentType();
      if(keyType != null && valueType != null) {
        MapProperty mapProperty = new MapProperty();
        Property innerType = getPrimitiveProperty(_typeName(valueType));
        if(innerType == null) { 
          String propertyTypeName = _typeName(valueType);
          Model innerModel =context.resolve(valueType); 
          if(innerModel != null) {
        	  context.defineModel(propertyTypeName, innerModel);
            if(!"Object".equals(propertyTypeName)) {              
              innerType = new RefProperty(propertyTypeName);
              mapProperty.additionalProperties(innerType);
              property = mapProperty;
            }
            else {
              innerType = new StringProperty();
              mapProperty.additionalProperties(innerType);
              property = mapProperty;
            }
          }
        }
        else {
          mapProperty.additionalProperties(innerType);
          property = mapProperty;
        }
      }
      else if(valueType != null) {
        ArrayProperty arrayProperty = new ArrayProperty();
        Property innerType = getPrimitiveProperty(_typeName(valueType));
        if(innerType == null) {
          String propertyTypeName = _typeName(valueType);
          Model innerModel = context.resolve(valueType);
          if(innerModel != null) {
            Class<?> cls = propType.getRawClass();
            if(cls != null) {
              if(java.util.Set.class.equals(cls))
                arrayProperty.setUniqueItems(true);
              else {
                for(Class<?> a : cls.getInterfaces()) {
                  if(java.util.Set.class.equals(a)) {
                    arrayProperty.setUniqueItems(true);
                  }
                }
              }
            }
            context.defineModel(propertyTypeName, innerModel);
            innerType = new RefProperty(propertyTypeName);
            arrayProperty.setItems(innerType);
            property = arrayProperty;
          }
        }
        else {
          if(keyType == null) {
            Class<?> cls = propType.getRawClass();
            if(cls != null) {
              for(Class<?> a : cls.getInterfaces()) {
                if(java.util.Set.class.equals(a))
                  arrayProperty.setUniqueItems(true);
              }
            }
          }

          arrayProperty.setItems(innerType);
          property = arrayProperty;
        }
      }
    }

    if(property == null) {
      // complex type
      String propertyTypeName = _typeName(propType);
      Model innerModel =  context.resolve(propType);      
      if(innerModel != null) {      
        context.defineModel(propertyTypeName, innerModel);
        property = new RefProperty(propertyTypeName);
      }
    }

    return property;
  }
  
  public Model resolve(Type type,ModelConverterContext context) {
    return resolve(_mapper.constructType(type),context);
  }

  public Model resolve(JavaType type, ModelConverterContext context) {
    final BeanDescription beanDesc = _mapper.getSerializationConfig().introspect(type);
    
    // Couple of possibilities for defining
    String name = _typeName(type, beanDesc);

    if("Object".equals(name)) {
	    return new ModelImpl();
    }
    
    if(type.isMapLikeType()) {
      return null;
    }

    ModelImpl model = new ModelImpl()
      .name(name)
      .description(_description(beanDesc.getClassInfo()));

    // if XmlRootElement annotation, construct an Xml object and attach it to the model
    XmlRootElement rootAnnotation = beanDesc.getClassAnnotations().get(XmlRootElement.class);
    if(rootAnnotation != null && rootAnnotation.name() != null && !"".equals(rootAnnotation.name())) {
      LOGGER.debug(rootAnnotation.toString());
      Xml xml = new Xml()
        .name(rootAnnotation.name());
      if(rootAnnotation.namespace() != null && !"".equals(rootAnnotation.namespace()) && !"##default".equals(rootAnnotation.namespace()))
        xml.namespace(rootAnnotation.namespace());
      model.xml(xml);
    }

    ApiModel apiModel = beanDesc.getClassAnnotations().get(ApiModel.class);
    // TODO
    if (apiModel != null) {
      if(apiModel.value() != null && !"".equals(apiModel.value())) {
        name = apiModel.value();
        model.setName(name);
      }
      Class<?> parent = apiModel.parent();
      if (parent != Void.class) {
        // model.setBaseModel(_typeName(_mapper.constructType(parent)));
      }
    }

    String disc = (apiModel == null) ? "" : apiModel.discriminator();
    if (disc.isEmpty()) {
      // longer method would involve AnnotationIntrospector.findTypeResolver(...) but:
      JsonTypeInfo typeInfo = beanDesc.getClassAnnotations().get(JsonTypeInfo.class);
      if (typeInfo != null) {
        disc = typeInfo.property();
      }
    }
    if (!disc.isEmpty()) {
      model.setDiscriminator(disc);
    }

    List<Property> props = new ArrayList<Property>();
    for (BeanPropertyDefinition propDef : beanDesc.findProperties()) {
      Property property = null;
      String propName = propDef.getName();

      // hack to avoid clobbering properties with get/is names
      // it's ugly but gets around https://github.com/swagger-api/swagger-core/issues/415
      if(propDef.getPrimaryMember() != null) {
        java.lang.reflect.Member member = propDef.getPrimaryMember().getMember();
        if(member != null) {
          String altName = member.getName();
          if(altName != null) {
            if(altName.startsWith("get")) {
              if(!Character.isUpperCase(altName.charAt(3))) {
                propName = altName;
              }
            }
            else if (altName.startsWith("is")) {
              if(!Character.isUpperCase(altName.charAt(2))) {
                propName = altName;
              }
            }
          }
        }
      }

      PropertyMetadata md = propDef.getMetadata();

      final AnnotatedMember member = propDef.getPrimaryMember();
      if(member != null) {
        JavaType propType = member.getType(beanDesc.bindingsForBeanType());
        property =context.resolveProperty(propType);

        if(property != null) {
          property.setName(propName);

          Boolean required = md.getRequired();
          if(required != null)
            property.setRequired(required);

          String description = _intr.findPropertyDescription(member);
          if(description != null && !"".equals(description))
            property.setDescription(description);

          Integer index = _intr.findPropertyIndex(member);
          if (index != null) {
            property.setPosition(index);
          }
          property.setExample(_findExampleValue(member));
          property.setReadOnly(_findReadOnly(member));

          if(property != null) {
            // check for XML annotations
            XmlElementWrapper wrapper = member.getAnnotation(XmlElementWrapper.class);

            if(wrapper != null) {
              Xml xml = new Xml();
              xml.setWrapped(true);

              if(wrapper.name() != null) {
                if("##default".equals(wrapper.name()))
                  xml.setName(propName);
                else if(!"".equals(wrapper.name()))
                  xml.setName(wrapper.name());
              }
              if(wrapper.namespace() != null && !"".equals(wrapper.namespace()) && !"##default".equals(wrapper.namespace()))
                xml.setNamespace(wrapper.namespace());

              property.setXml(xml);
            }

            XmlElement element = member.getAnnotation(XmlElement.class);
            if(element != null) {
              if(element.name() != null && !"".equals(element.name())) {
                // don't set Xml object if name is same
                if(!element.name().equals(propName) && !"##default".equals(element.name())) {
                  Xml xml = property.getXml();
                  if(xml == null) {
                    xml = new Xml();
                    property.setXml(xml);
                  }
                  xml.setName(element.name());
                }
              }
            }
          }
          props.add(property);
        }
      }
    }

    List<NamedType> nts = _intr.findSubtypes(beanDesc.getClassInfo());
    if (nts != null) {
      ArrayList<String> subtypeNames = new ArrayList<String>();
      for (NamedType subtype : nts) {
        Model subtypeModel = context.resolve(subtype.getType());

        if(subtypeModel instanceof ModelImpl && subtypeModel != null) {
          ModelImpl impl = (ModelImpl) subtypeModel;

          // remove shared properties defined in the parent
          if(model.getProperties() != null) {
            for(String propertyName : model.getProperties().keySet()) {
              if(impl.getProperties().containsKey(propertyName)) {
                impl.getProperties().remove(propertyName);
              }
            }
          }

          impl.setDiscriminator(null);
          ComposedModel child = new ComposedModel()
            .parent(new RefModel(name))
            .child(impl);		
          context.defineModel(impl.getName(), child);
        }
      }
    }

    Collections.sort(props, getPropertyComparator());

    Map<String, Property> modelProps = new LinkedHashMap<String, Property>();
    for (Property prop : props) {
      modelProps.put(prop.getName(), prop);
    }
    model.setProperties(modelProps);
    context.defineModel(name, model);
    return model;
  }
}
