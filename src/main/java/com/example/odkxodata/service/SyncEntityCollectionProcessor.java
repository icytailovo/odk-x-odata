package com.example.odkxodata.service;

import lombok.SneakyThrows;
import lombok.extern.java.Log;
import org.apache.olingo.commons.api.data.*;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpHeader;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.*;
import org.apache.olingo.server.api.processor.EntityCollectionProcessor;
import org.apache.olingo.server.api.serializer.EntityCollectionSerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;
import org.opendatakit.sync.client.SyncClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * The class is an OData Processor that is only responsible for handling a collection of entities.
 * Specifically, it is responsible for retrieving a SyncClient table's all data
 */
@Component
@Log
public class SyncEntityCollectionProcessor implements EntityCollectionProcessor {

    @Resource
    SyncDataConverter syncDataConverter;

    @Resource
    SyncEdmProvider syncEdmProvider;

    @Value("${odk.nulloutput.allow}")
    private boolean allowNullOutput;
    private OData odata;
    private ServiceMetadata serviceMetadata;

    /**
     * Initialize the processor with an instance of the OData object and store the context object
     * @param odata an instance of the OData object
     * @param serviceMetadata Entity Data Model and current service metadata
     */
    public void init(OData odata, ServiceMetadata serviceMetadata) {
        this.odata = odata;
        this.serviceMetadata = serviceMetadata;
    }

    /**
     * Take an ODataRequest and fetch the corresponding data through Sync Protocol.
     * Return the serialized data based on Entity Data Model in an ODataResponse
     * Invoked when the OData service is called with an HTTP GET operation for an entity collection(table data).
     *
     * @param request an ODataRequest that has request body and headers information from client
     * @param response an ODataResponse that will be set and sent to client
     * @param uriInfo an UriInfo that describes the request URI
     * @param responseFormat a ContentType represents the request body format
     * @throws ODataApplicationException
     * @throws SerializerException
     */
    @SneakyThrows
    public void readEntityCollection(ODataRequest request, ODataResponse response,
                                     UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {

        // retrieve the requested EntitySet from the uriInfo object (representation of the parsed service URI)
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
        log.info("readEntityCollection: " + resourcePaths);
        // the first segment is the EntitySet
        UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resourcePaths.get(0);
        EdmEntitySet edmEntitySet = uriResourceEntitySet.getEntitySet();

        // fetch the data for this requested tableId through Sync Protocol
        String tableId = edmEntitySet.getName();
        EntityCollection entitySet = this.getEntitySetData(tableId);

        // create a serializer based on the requested format (json)
        ODataSerializer serializer = odata.createSerializer(responseFormat);

        // serialize the content: transform from the EntitySet object the InputStream
        EdmEntityType edmEntityType = edmEntitySet.getEntityType();
        ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).build();

        final String id = request.getRawBaseUri() + "/" + edmEntitySet.getName();
        EntityCollectionSerializerOptions opts = EntityCollectionSerializerOptions.with().id(id).contextURL(contextUrl).build();
        SerializerResult serializerResult = serializer.entityCollection(serviceMetadata, edmEntityType, entitySet, opts);
        InputStream serializedContent = serializerResult.getContent();

        // configure the response object: set the body, headers and status code
        response.setContent(serializedContent);
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
    }

    /**
     * Take a tableId and transform the relevant Sync Protocol table values to a list of entities
     * and return the list in an EntityCollection
     * @param tableId the table identifier or name
     * @return an EntityCollection that contains a list of entities
     * @throws JSONException
     */
    private EntityCollection getEntitySetData(String tableId) throws JSONException {
        JSONArray rows = syncDataConverter.getRows(tableId);
        EntityCollection entityCollection = new EntityCollection();

        Map<String, FullQualifiedName> tableDef = syncEdmProvider.getTableDefMap(tableId);

        // format all rows value to a list of entities
        for (int i = 0; i < rows.size(); i++) {
            final Entity e = new Entity();
            // each row need to have id, createUser and lastUpdateUser, which should be set manually
            String id = rows.getJSONObject(i).getString("id");
            String createUser = rows.getJSONObject(i).getString("createUser");
            String lastUpdateUser = rows.getJSONObject(i).getString("lastUpdateUser");
            e.addProperty(new Property("String", "Row Id", ValueType.PRIMITIVE, id))
                    .addProperty(new Property("String", "Create User", ValueType.PRIMITIVE, createUser))
                    .addProperty(new Property("String", "Update User", ValueType.PRIMITIVE, lastUpdateUser));

            // get single row data
            JSONArray row = rows.getJSONObject(i).getJSONArray(SyncClient.ORDERED_COLUMNS_DEF);
            // reformat each row value to an entity property
            for (int j = 0; j < row.size(); j++) {
                JSONObject rowKV = row.getJSONObject(j);
                String rowKey = rowKV.getString("column");
                String rowValue = (String) rowKV.get("value");
                if (tableDef.containsKey(rowKey)) {
                    e.addProperty(formatProperty(tableDef.get(rowKey), rowKey, rowValue));
                }
            }
            entityCollection.getEntities().add(e);
        }
        return entityCollection;
    }

    /**
     * Format a row's single field value into an OData property and return it
     * @param OdataType OdataType in the type of FullQualifiedName
     * @param colName column name in the type of String
     * @param rowValue one row's single field value in the type of String
     * @return a Property that represents a row's single field value of a table
     */
    private Property formatProperty(FullQualifiedName OdataType, String colName, String rowValue) {
        String type = OdataType.getName();
        // for String type (note all non-integer, non-double and non-boolean types are represented as String Type)
        // null values, set the property value be "" so that client side's only have very few null values in their data
        if (allowNullOutput && type.equals("String") && rowValue == null) {
            return new Property(null, colName, ValueType.PRIMITIVE, "");
        }
        // for non-String type(integer, double, boolean) null values, we need to set valid values with
        // given type and can only be null
        // TODO: find a potential solution to get rid of all null values
        if (rowValue == null) {
            return new Property(null, colName, ValueType.PRIMITIVE, null);
        }
        // convert the non-null rowValue to a specific type property
        // by default, the value is a String type
        Object value = rowValue;
        if (type.equals("Int32")) {
            value = Integer.valueOf(rowValue);
        } else if (type.equals("Double")) {
            value = Double.valueOf(rowValue);
        } else if (type.equals("Boolean")) {
            value = Boolean.valueOf(rowValue);
        }
        Property p = new Property(null, colName, ValueType.PRIMITIVE, value);
        return p;
    }

}
