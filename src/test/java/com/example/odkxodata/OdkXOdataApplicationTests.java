package com.example.odkxodata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * This test used https://survey-demo.odk-x.org for testing
 */
@TestPropertySource(locations="classpath:test.properties")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class OdkXOdataApplicationTests {
    private static final String username = "demo_user1";
    private static final String password = "password";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment env;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Before
    public void setup() {

    }

    /**
     * Test for getting all tables name with http://127.0.0.1:8080/OData/V1.0/
     * @throws Exception
     */
    @Test
    public void testGetAllTables() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, password);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity request = new HttpEntity(headers);
        String url = env.getProperty("server.root");

        ResponseEntity<String> response = this.restTemplate.exchange(url + "/", HttpMethod.GET, request, String.class);
        JsonNode root = objectMapper.readTree(response.getBody());

        Assertions.assertEquals(response.getStatusCode(), HttpStatus.OK);
        JsonNode values = root.get("value");
        if (values.isArray()) {
            for (JsonNode value : values) {
                Assertions.assertNotNull(value.get("name").textValue());
                Assertions.assertNotNull(value.get("url").textValue());
            }
        }

    }

    /**
     * Test for getting metadata for a single/all table(s) with http://127.0.0.1:8080/OData/V1.0/$metadata
     * @throws Exception
     */
    @Test
    public void testMetadata() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, password);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity request = new HttpEntity(headers);
        String url = env.getProperty("server.root");

        ResponseEntity<String> response = this.restTemplate.exchange(url + "/$metadata", HttpMethod.GET, request, String.class);
        JsonNode root = objectMapper.readTree(response.getBody());

        Assertions.assertEquals(response.getStatusCode(), HttpStatus.OK);
        Assertions.assertNotNull(root.get(env.getProperty("odk.namespace")));
        JsonNode entityTypesAndContainer = root.get(env.getProperty("odk.namespace"));

        JsonNode entityContainer = entityTypesAndContainer.get(env.getProperty("odk.container"));
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = entityContainer.fieldNames();
        iterator.forEachRemaining(e -> keys.add(e));
        List<String> entityTypes = new ArrayList<>();
        for (String key : keys) {
            if (key.equals("$Kind")) {
                Assertions.assertEquals(entityContainer.get(key).textValue(), "EntityContainer");
            } else {
                entityTypes.add(key);
                Assertions.assertEquals(entityContainer.get(key).get("$Kind").textValue(), "EntitySet");
                Assertions.assertEquals(entityContainer.get(key).get("$Type").textValue(), env.getProperty("odk.namespace")+ "." + key);
            }
        }

        for (String entityType : entityTypes) {
            Assertions.assertNotNull(entityTypesAndContainer.get(entityType));
            Assertions.assertEquals(entityTypesAndContainer.get(entityType).get("$Kind").textValue(), "EntityType");
            Assertions.assertEquals(entityTypesAndContainer.get(entityType).get("Create User").get("$Type").textValue(), "Edm.String");
        }

    }

    /**
     * Test for getting metadata for a single table's (household) data with http://127.0.0.1:8080/OData/V1.0/household
     * @throws Exception
     */
    @Test
    public void testGetTableData() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, password);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity request = new HttpEntity(headers);
        String url = env.getProperty("server.root");

        ResponseEntity<String> response = this.restTemplate.exchange(url + "/household", HttpMethod.GET, request, String.class);
        JsonNode root = objectMapper.readTree(response.getBody());

        Assertions.assertEquals(response.getStatusCode(), HttpStatus.OK);
        Assertions.assertEquals(root.get("@odata.context").textValue(), "$metadata#household");
        JsonNode values = root.get("value");
        System.out.println(values);
        if (values.isArray()) {
            for (JsonNode value : values) {
                System.out.println(value);
            }
        }

    }


}
