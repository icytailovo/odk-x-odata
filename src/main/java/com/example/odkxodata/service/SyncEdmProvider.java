package com.example.odkxodata.service;

import lombok.extern.java.Log;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.provider.*;
import org.apache.olingo.commons.api.ex.ODataException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Class used as the implementation of the Entity Data Model(EDM), which is the underlying metadata model of
 * the OData protocol. Note that some methods used only for the output of the metadata document.
 */
@Component
@Log
public class SyncEdmProvider extends CsdlAbstractEdmProvider {
    // Service Namespace
    @Value("${odk.namespace}")
    public String namespace;

    // EDM Container
    @Value("${odk.container}")
    public String containerName;
    public final FullQualifiedName CONTAINER = new FullQualifiedName(namespace, containerName);

    // store all tables' id and etag for the current client to avoid unnecessary calls to SyncClient
    private List<SyncDataConverter.TableInfo> tableInfos;
    // the map that maps from table id to table's definition
    private final Map<String, Map<String, FullQualifiedName>> tableIdToDefMap = new HashMap<>();

    @Autowired
    SyncDataConverter syncDataConverter;

    /**
     * Return an EntityType with its properties that are configured in the Schema
     * @param entityTypeName an entity type's name in the type of FullQualifiedName
     * @return a CsdlEntityType representing an EntityType
     */
    @Override
    public CsdlEntityType getEntityType(FullQualifiedName entityTypeName) throws ODataException {
        // get corresponding table id and schemaEtag (tableInfo) based on the entityTypeName
        SyncDataConverter.TableInfo tableInfo = null;
        for (SyncDataConverter.TableInfo tableInfoEntry : tableInfos) {
            if (tableInfoEntry.getTableId().equals(entityTypeName.getName())) {
                tableInfo = tableInfoEntry;
                break;
            }
        }
        if (tableInfo == null) {
            return null;
        }
        String tableId = tableInfo.getTableId();
        Map<String, FullQualifiedName> tableDef;

        // Only retrieve the missing table definition through Sync Protocol to speed up
        if (!tableIdToDefMap.containsKey(tableId)) {
            tableDef = syncDataConverter.getTableDefinition(tableInfo);
            tableIdToDefMap.put(tableId, tableDef);
        } else {
            tableDef = tableIdToDefMap.get(tableId);
        }
        // format table definition into EntityType properties
        List<CsdlProperty> properties = formatProperties(tableDef);

        // create CsdlPropertyRef for Key element
        CsdlPropertyRef propertyRef = new CsdlPropertyRef();
        propertyRef.setName("Row Id");

        // configure EntityType
        CsdlEntityType entityType = new CsdlEntityType();
        // note that entity type's name is tableId, which matches corresponding entity set's type
        entityType.setName(entityTypeName.getName());
        entityType.setProperties(properties);
        entityType.setKey(Collections.singletonList(propertyRef));
        return entityType;
    }

    /**
     * Return an EntitySet which will be used to request data
     * @param entityContainer an entity set's name in the type of String
     * @param entitySetName an entityContainer in the type of FullQualifiedName
     * @return an EntitySet in the type of CsdlEntitySet
     */
    @Override
    public CsdlEntitySet getEntitySet(FullQualifiedName entityContainer, String entitySetName) throws ODataException {
        if (entityContainer.equals(CONTAINER)) {
            CsdlEntitySet entitySet = new CsdlEntitySet();
            entitySet.setName(entitySetName);
            // note that entity set's type is tableId, and it is referred by a FullQualifiedName.
            // It matches corresponding entity type's name
            entitySet.setType(new FullQualifiedName(namespace, entitySetName));
            return entitySet;
        }
        return null;
    }

    /**
     * Return an EntityContainer's info about the EntityContainer to be displayed in the Service Document
     * @param entityContainerName an entity container's name in the type of FullQualifiedName
     * @return an EntityContainer's info in the type of CsdlEntityContainerInfo
     */
    @Override
    public CsdlEntityContainerInfo getEntityContainerInfo(FullQualifiedName entityContainerName) throws ODataException {
        log.info("getEntityContainerInfo");

        if (entityContainerName == null || entityContainerName.equals(CONTAINER)) {
            CsdlEntityContainerInfo entityContainerInfo = new CsdlEntityContainerInfo();
            entityContainerInfo.setContainerName(CONTAINER);
            return entityContainerInfo;
        }
        return null;
    }

    /**
     * Return a list of schemas (only need one schema) such that each schema is the root element to carry the elements.
     * @return all schemas in a list of CsdlSchema
     */
    @Override
    public List<CsdlSchema> getSchemas() throws ODataException {
        log.info("getSchemas");
        // create Schema
        CsdlSchema schema = new CsdlSchema();
        schema.setNamespace(namespace);

        // add EntityTypes
        List<CsdlEntityType> entityTypes = new ArrayList<>();
        for (SyncDataConverter.TableInfo tableInfo : tableInfos) {
            entityTypes.add(getEntityType(new FullQualifiedName(namespace, tableInfo.getTableId())));
        }
        schema.setEntityTypes(entityTypes);

        // add EntityContainer
        schema.setEntityContainer(getEntityContainer());

        // finally
        List<CsdlSchema> schemas = new ArrayList<>();
        schemas.add(schema);

        return schemas;
    }

    /**
     * Return an EntityContainer that carries EntitySets to provide data
     * @return an EntityContainer in the type of CsdlEntityContainer
     */
    @Override
    public CsdlEntityContainer getEntityContainer() throws ODataException {
        log.info("getEntityContainer");
        // create EntitySets
        List<CsdlEntitySet> entitySets = new ArrayList<>();
        // go through each table to get corresponding EntitySet
        for (SyncDataConverter.TableInfo tableInfo : tableInfos) {
            entitySets.add(getEntitySet(CONTAINER, tableInfo.getTableId()));
        }

        // create EntityContainer
        CsdlEntityContainer entityContainer = new CsdlEntityContainer();
        entityContainer.setName(containerName);
        entityContainer.setEntitySets(entitySets);

        return entityContainer;
    }

    /**
     * Update tableInfos with a new list of TableInfo
     * @param tableInfos a list of TableInfo that has all tables' tableId and schemaEtag
     */
    public void updateTableIds(List<SyncDataConverter.TableInfo> tableInfos) {
        if (!tableInfos.isEmpty() && !tableInfos.equals(this.tableInfos)) {
            tableIdToDefMap.clear();
            this.tableInfos = tableInfos;
        }
    }

    /**
     * Take a table's definition map and reformat them into EntityType properties
     * @param tableDef a map that maps from column name to OData primitive type
     * @return list of CsdlProperty that represents EntityType properties
     */
    private List<CsdlProperty> formatProperties(Map<String, FullQualifiedName> tableDef) {
        List<CsdlProperty> properties = new ArrayList<>();
        properties.add(new CsdlProperty().setName("Row Id").setType(EdmPrimitiveTypeKind.String.getFullQualifiedName()));
        properties.add(new CsdlProperty().setName("Create User").setType(EdmPrimitiveTypeKind.String.getFullQualifiedName()));
        properties.add(new CsdlProperty().setName("Update User").setType(EdmPrimitiveTypeKind.String.getFullQualifiedName()));
        for (Map.Entry<String, FullQualifiedName> e : tableDef.entrySet()) {
            CsdlProperty property = new CsdlProperty()
                                        .setName(e.getKey())
                                        .setType(e.getValue());
            properties.add(property);
        }
        return properties;
    }

    /**
     * Return a specific table's definition in a map that maps from column name to OData primitive type
     * @param tableId the table identifier or name
     * @return a map that maps from column name to OData primitive type
     */
    public Map<String, FullQualifiedName> getTableDefMap(String tableId) {
        return tableIdToDefMap.get(tableId);
    }
}
