# Sync Endpoint OData Feed

## Prerequisites
- Git
- Maven
- An OData feed/connector supported BI system (e.g. PowerBI, Tableau)
- A working ODK-X SyncEndpoint service

### Recommended
- a Java IDE (e.g. Eclipse or IntelliJ IDEA)

## Build
To build artifacts and run tests use the `mvn clean install` command.

## Configuration

`application.properties` hold configuration for the entire service, and the following fields have to be changed:
- `odk.server.url`: ODK-X SyncEndpoint server url
- `server.address`: current OData service server address
- `server.port`: current OData service server port
- `server.root`: current OData service server root path (e.g. /OData/V1.0)

`test.properties` hold configuration for the test

## Run
1. Setup configuration properly in `application.properties`
2. Use `mvn spring-boot:run` command to run the service or go to `OdkXOdataApplication` and run the service manually
3. Open an OData feed/connector supported BI system (e.g. PowerBI, Tableau) and select OData feed/connector option
4. Type server url (here is Odata service server url, not ODK-X SyncEndpoint server url) username and password  
   Note: server url is `{server.address}:{server.port}{server.root}/` (e.g. http://127.0.0.1:8080/OData/V1.0)
5. After few seconds, service should be connected successfully and can interact with the BI system to get related data

## Additional Configuration
- Enable `odk.nulloutput.allow` field in `application.properties` will mark all blank field data in the table as null. By default `odk.nulloutput.allow=true`   
  Note that disable `odk.nulloutput.allow` will:
  - still mark all non-integer and non-float blank field data in the table as null
  - leave the rest of blank field data in the table as blank

## Notes

The service is implemented with Apache Olingo4 Library, [here are the documentation](https://olingo.apache.org/doc/odata4/index.html)

#### Potential Improvement
- Allow all data types of blank field data in the table as blank
- Add more security 
- Send useful error message (e.g. invalid credentials)