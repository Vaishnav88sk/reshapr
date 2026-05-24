package io.reshapr.ctrl.webhook;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequestBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReviewBuilder;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

@QuarkusTest
public class MutatingWebhookResourceTest {

    @Inject
    ObjectMapper objectMapper;

    @Test
    public void testMutateWithInjection() throws Exception {
        Pod pod = new PodBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("test-pod")
                        .withAnnotations(Map.of("io.reshapr/inject", "true"))
                        .build())
                .withNewSpec()
                .addNewContainer()
                .withName("app-container")
                .withImage("app-image")
                .endContainer()
                .endSpec()
                .build();

        AdmissionRequest request = new AdmissionRequestBuilder()
                .withUid("test-uid")
                .withNewKind("", "Pod", "v1")
                .withObject(pod)
                .build();

        AdmissionReview review = new AdmissionReviewBuilder()
                .withRequest(request)
                .build();

        AdmissionReview response = given()
                .contentType(ContentType.JSON)
                .body(review)
                .when()
                .post("/api/v1/webhooks/mutate")
                .then()
                .statusCode(200)
                .extract()
                .as(AdmissionReview.class);

        assertTrue(response.getResponse().getAllowed());
        assertEquals("test-uid", response.getResponse().getUid());
        assertEquals("JSONPatch", response.getResponse().getPatchType());
        
        String decodedPatch = new String(Base64.getDecoder().decode(response.getResponse().getPatch()));
        assertTrue(decodedPatch.contains("reshapr-proxy"));
    }

    @Test
    public void testMutateWithoutInjection() {
        Pod pod = new PodBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("test-pod")
                        .build())
                .withNewSpec()
                .addNewContainer()
                .withName("app-container")
                .withImage("app-image")
                .endContainer()
                .endSpec()
                .build();

        AdmissionRequest request = new AdmissionRequestBuilder()
                .withUid("test-uid")
                .withNewKind("", "Pod", "v1")
                .withObject(pod)
                .build();

        AdmissionReview review = new AdmissionReviewBuilder()
                .withRequest(request)
                .build();

        AdmissionReview response = given()
                .contentType(ContentType.JSON)
                .body(review)
                .when()
                .post("/api/v1/webhooks/mutate")
                .then()
                .statusCode(200)
                .extract()
                .as(AdmissionReview.class);

        assertTrue(response.getResponse().getAllowed());
        assertEquals("test-uid", response.getResponse().getUid());
        assertTrue(response.getResponse().getPatch() == null || response.getResponse().getPatch().isEmpty());
    }
}
