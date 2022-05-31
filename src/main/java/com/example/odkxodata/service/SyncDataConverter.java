package com.example.odkxodata.service;

import lombok.Data;
import lombok.extern.java.Log;
import org.apache.http.client.ClientProtocolException;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;
import org.opendatakit.sync.client.SyncClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.util.*;

/**
 * Class used to initialize a SyncClient and get related table data through Sync Protocol. It will convert and reformat
 * the data based on needs
 */
@Service
@Log
public class SyncDataConverter {
    @Value("${odk.server.appId}")
    private String appId;
    @Value("${odk.server.url}")
    private String aggUrl;

    private String[] credentials;
    private SyncClient wc;
    public List<TableInfo> tableInfos;

    /**
     * Initialize the SyncClient based on the given credentials information
     * @param credentials user credentials details in the form of [username, password]
     */
    public void initSyncClient(String[] credentials) {
        // same credentials, no need to initialize a new SyncClient
        if (Arrays.equals(this.credentials,credentials)) {
            return;
        }
        wc = new SyncClient();
        String absolutePathOfTestFiles = "testfiles/test/";
        int batchSize = 1000;
        tableInfos = new ArrayList<>();
        try {
            URL url = new URL(aggUrl);
            String host = url.getHost();
            String userName = credentials[0];
            String password = credentials[1];

            wc.init(host, userName, password);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        this.credentials = credentials;
    }

    /**
     * Return a map that maps from column name to OData primitive type representing the given table's definition
     * @param tableInfo the table's tableId and schemaEtag in the type of TableInfo
     * @return a map that maps from column name to OData primitive type
     */
    public Map<String, FullQualifiedName> getTableDefinition(TableInfo tableInfo) {
        String tableId = tableInfo.getTableId();
        String tableSchemaETag = tableInfo.getSchemaEtag();
        Map<String, FullQualifiedName> colNameToODataTypeMap = new TreeMap<>();
        try {
            JSONObject tableDef = wc.getTableDefinition(aggUrl, appId, tableId, tableSchemaETag);
            JSONArray tableColsDef = tableDef.getJSONArray(SyncClient.ORDERED_COLUMNS_DEF);
            // record parent's name and its single child's name
            // this is because on SyncEndpoint web ui, we show parent's name with the single child's type
            String[] parentAndChild = new String[2];
            for (int i = 0; i < tableColsDef.size(); i++) {
                JSONObject colDef = tableColsDef.getJSONObject(i);
                String colType = colDef.getString("elementType");
                String colName = colDef.getString("elementKey");
                String childElements = colDef.getString("listChildElementKeys");
                // ignore current element if multiple child element keys exist
                if (childElements.indexOf(",") >= 0) {
                    continue;
                }
                // the parent has a single child
                if (childElements.length() > 2) {
                    parentAndChild[0] = colName;
                    parentAndChild[1] = childElements.substring(2, childElements.length() - 2);
                    continue;
                }
                // assign the parent with the type of its single child
                if (colName.equals(parentAndChild[1])) {
                    colName = parentAndChild[0];
                    parentAndChild = new String[2];
                }
                // need to convert the Sync col type to Qualified Type
                colNameToODataTypeMap.put(colName, colTypeToODataType(colType));
            }
            return colNameToODataTypeMap;
        } catch (IOException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Return a corresponding OData primitive type based on the given Sync Protocol column type
     * @param type Sync Protocol column type
     * @return a FullQulifiedName with OData primitive type
     */
    private FullQualifiedName colTypeToODataType(String type) {
        if (type.equals("integer")) {
            return EdmPrimitiveTypeKind.Int32.getFullQualifiedName();
        }
        if (type.equals("number")) {
            return EdmPrimitiveTypeKind.Double.getFullQualifiedName();
        }
        if (type.equals("boolean")) {
            return EdmPrimitiveTypeKind.Boolean.getFullQualifiedName();
        }
        return EdmPrimitiveTypeKind.String.getFullQualifiedName();
    }

    /**
     * Return a list of TableInfo that represents current client's all tables' information(tableId and schemaEtag)
     * @return a list of TableInfo
     */
    public List<TableInfo> getTables() {
        // directly return the previous result if there is any
        if (!this.tableInfos.isEmpty()) {
            return this.tableInfos;
        }
        log.info("Get all tables' tableId and schemaEtag");
        List<TableInfo> newTableInfos = new ArrayList<>();
        try {
            JSONObject tablesInfo = wc.getTables(aggUrl, appId);
            JSONArray tables = tablesInfo.getJSONArray(SyncClient.TABLES_JSON);
            for (int i = 0; i < tables.size(); i++) {
                JSONObject table = tables.getJSONObject(i);
                newTableInfos.add(new TableInfo(table.getString(SyncClient.TABLE_ID_JSON), table.getString(SyncClient.SCHEMA_ETAG_JSON)));
            }
            this.tableInfos = newTableInfos;
        } catch (ClientProtocolException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newTableInfos;
    }

    /**
     * Return all rows of a table with given tableId in a JSONArray
     * @param tableId the table identifier or name
     * @return a JSONArray with all rows of a table
     */
    public JSONArray getRows(String tableId) {
        log.info("Get all rows of a table with tableId: " + tableId);
        String tableSchemaETag = null;
        for (TableInfo tableInfo : tableInfos) {
            if (tableInfo.getTableId().equals(tableId)) {
                tableSchemaETag = tableInfo.getSchemaEtag();
                break;
            }
        }
        if (tableSchemaETag == null) {
            return null;
        }
        try {
            JSONObject tableData = wc.getRows(aggUrl, appId,tableId, tableSchemaETag, null,null);
            JSONArray rows = tableData.getJSONArray(SyncClient.ROWS_STR);
            return rows;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Data
    /**
     * Store the information about table's id and schemaEtag
     */
    public static class TableInfo {
        private String tableId;
        private String schemaEtag;

        public TableInfo(String tableId, String schemaEtag) {
            this.tableId = tableId;
            this.schemaEtag = schemaEtag;
        }
    }
}
