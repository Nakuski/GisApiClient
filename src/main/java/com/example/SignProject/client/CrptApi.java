package com.example.SignProject.client;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.LocalDateTime;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {
    private static final String DOCUMENT_FORMAT = "MANUAL";
    private static final String DOCUMENT_TYPE = "AGGREGATION_DOCUMENT";
    public static final String CONTENT_TYPE = "content-type";
    public static final String APPLICATION_JSON = "application/json;charset=UTF-8";

    private final Lock lock = new ReentrantLock();
    private final String API_URL;
    private final Semaphore semaphore;

    private final TimeUnit timeUnit;
    private final int requestLimit;

    private volatile LocalDateTime lastRefresh;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObjectWriter objectWriter = objectMapper.writer().withDefaultPrettyPrinter();


    public CrptApi(TimeUnit timeUnit, int requestLimit, @Value("${crpt.api.url}") String apiUrl) {
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.semaphore = new Semaphore(requestLimit, true);
        API_URL = apiUrl;
    }

    public String processDocumentCreation(Document document, String sign, String productGroup) {
        while (!semaphore.tryAcquire())
        {
            lock.lock();
            if (timeUnit.toChronoUnit().between(LocalDateTime.now(), lastRefresh) > 1) {
                lastRefresh = LocalDateTime.now();
                semaphore.release(requestLimit);
            }
            lock.unlock();
        }
        return createDocument(document, sign, productGroup).getValue();
    }

    private DocumentCreationResponse createDocument(Document document, String sign, String productGroup) {

        DocumentCreationRequest documentRequest = createDocumentRequest(document, sign, productGroup);

        HttpRequest request = HttpRequest.newBuilder()
                .POST(BodyPublishers.ofString(mapToString(documentRequest)))
                .uri(URI.create(API_URL + "/lk/documents/create?pg=" + productGroup))
                .setHeader(CONTENT_TYPE, APPLICATION_JSON)
                .setHeader("Authorization", "Bearer " + getAuthToken())
                .build();

        HttpResponse<String> response = sendRequest(request);
        return (DocumentCreationResponse) mapToObject(response.body(), DocumentCreationResponse.class);
    }

    private String getAuthToken() {

        HttpRequest request = HttpRequest.newBuilder()
                .POST(BodyPublishers.ofString(getAuthData()))
                .uri(URI.create(API_URL + "/auth/cert"))
                .setHeader(CONTENT_TYPE, APPLICATION_JSON)
                .build();

        HttpResponse<String> response = sendRequest(request);
        if (response.statusCode() == 200) {
            TokenResponse tokenResponse = (TokenResponse) mapToObject(response.body(), TokenResponse.class);
            return tokenResponse.getToken();
        } else {
            return StringUtils.EMPTY;
        }
    }

    private String getAuthData() {

        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(API_URL + "/auth/cert/key"))
                .setHeader(CONTENT_TYPE, APPLICATION_JSON)
                .build();

        HttpResponse<String> response = sendRequest(request);
        if (response.statusCode() == 200) {
            return response.body();
        } else {
            return StringUtils.EMPTY;
        }
    }

    private DocumentCreationRequest createDocumentRequest(Document document, String sign, String productGroup) {
        DocumentCreationRequest documentCreationRequest = new DocumentCreationRequest();
        documentCreationRequest.setDocumentFormat(DOCUMENT_FORMAT);
        documentCreationRequest.setProductDocument(mapToString(document));
        documentCreationRequest.setSignature(sign);
        documentCreationRequest.setProductGroup(productGroup);
        documentCreationRequest.setType(DOCUMENT_TYPE);

        return documentCreationRequest;
    }

    private HttpResponse<String> sendRequest(HttpRequest request) {
        HttpClient client = HttpClient.newHttpClient();
        try {
            return client.send(request, BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new CrptApiException("Fail during accessing CRPT API", e);
        }
    }

    private Object mapToObject(String json, Class clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new CrptApiException("Failed to convert from json", e);
        }
    }

    private String mapToString(Object object) {

        try {
            return objectWriter.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new CrptApiException("Failed to convert to json", e);
        }
    }

    public static class CrptApiException extends RuntimeException {

        public CrptApiException(String message) {
            super(message);
        }

        public CrptApiException(String message, Exception e) {
            super(message, e);
        }
    }

    public static class DocumentCreationRequest {
        private String documentFormat;
        private String productDocument;
        private String productGroup;
        private String signature;
        private String type;

        public String getDocumentFormat() {
            return documentFormat;
        }

        public void setDocumentFormat(String documentFormat) {
            this.documentFormat = documentFormat;
        }

        public String getProductDocument() {
            return productDocument;
        }

        public void setProductDocument(String productDocument) {
            this.productDocument = productDocument;
        }

        public String getProductGroup() {
            return productGroup;
        }

        public void setProductGroup(String productGroup) {
            this.productGroup = productGroup;
        }

        public String getSignature() {
            return signature;
        }

        public void setSignature(String signature) {
            this.signature = signature;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

    }

    public static class Document {
        private String documentInfo;

        public String getDocumentInfo() {
            return documentInfo;
        }

        public void setDocumentInfo(String documentInfo) {
            this.documentInfo = documentInfo;
        }
    }

    public static class DefaultResponse {
        private String code;
        private String errorMessage;
        private String description;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class TokenResponse extends DefaultResponse {
        private String token;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public static class DocumentCreationResponse extends DefaultResponse {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

}
