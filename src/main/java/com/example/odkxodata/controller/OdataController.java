package com.example.odkxodata.controller;

import com.example.odkxodata.service.SyncDataConverter;
import com.example.odkxodata.service.SyncEdmProvider;
import lombok.extern.java.Log;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataHttpHandler;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.processor.EntityCollectionProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The request controller that will dispatch all client requests to URLs below the service's root URL
 * to the OData handler class
 * TODO: handle invalid credentials
 */

@RestController
@RequestMapping("${server.root}")
@Log
public class OdataController {
    @Value("${server.root}")
    public String uri;

    @Autowired
    SyncEdmProvider edmProvider;

    @Autowired
    EntityCollectionProcessor entityCollectionProcessor;

    @Autowired
    SyncDataConverter syncDataConverter;

    /**
     * Handle client's request through OData process and sent back a corresponding response
     * @param request http request sent by the client
     * @param response http response that the client will receive
     */
    @RequestMapping(value = "*")
    public void process(HttpServletRequest request, HttpServletResponse response) {
        // pre-process
        log.info("Processing request: " + request.getRequestURI());
        String[] credentials = getCredentials(request);
        syncDataConverter.initSyncClient(credentials);
        List<SyncDataConverter.TableInfo> tableInfos = syncDataConverter.getTables();
        edmProvider.updateTableIds(tableInfos);

        // OData process, create odata handler and configure it with EdmProvider and Processor
        OData odata = OData.newInstance();
        ServiceMetadata edm = odata.createServiceMetadata(edmProvider, new ArrayList<>());
        ODataHttpHandler handler = odata.createHandler(edm);
        handler.register(entityCollectionProcessor);
        handler.process(new HttpServletRequestWrapper(request) {
            // It needs to be overridden because Olingo just wants the prefix part of the servlet path,
            // and the rest of the servlet path is served as OData path. While Spring MVC maps the entire path
            // as the servlet path
            @Override
            public String getServletPath() {
                return uri;
            }
        }, response);
        System.out.println();
    }

    /**
     * Return a user credentials details in the form of [username, password] based on the request
     * @param request http request sent by client
     * @return a String array with user credentials details in the form of [username, password]
     */
    private String[] getCredentials(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.toLowerCase().startsWith("basic")) {
            // Authorization: Basic base64credentials
            String base64Credentials = authorization.substring("Basic".length()).trim();
            byte[] credDecoded = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(credDecoded, StandardCharsets.UTF_8);
            // credentials = username:password
            return credentials.split(":", 2);
        }
        return new String[2];
    }
}

