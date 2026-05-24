package io.reshapr.ctrl.webhook;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponseBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReviewBuilder;

import io.github.vishwakarma.zjsonpatch.JsonDiff;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

@Path("/api/v1/webhooks/mutate")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MutatingWebhookResource {

    private static final Logger log = Logger.getLogger(MutatingWebhookResource.class);
    private static final String INJECT_ANNOTATION = "io.reshapr/inject";
    private static final String PROXY_IMAGE = "quay.io/reshapr/reshapr-proxy:latest";
    private static final String PROXY_CONTAINER_NAME = "reshapr-proxy";

    @Inject
    ObjectMapper objectMapper;

    @POST
    public AdmissionReview mutate(AdmissionReview review) {
        AdmissionRequest request = review.getRequest();
        if (request == null) {
            return review;
        }

        String uid = request.getUid();
        
        // We only care about Pods
        if (!"Pod".equals(request.getKind().getKind())) {
            return new AdmissionReviewBuilder()
                    .withResponse(new AdmissionResponseBuilder()
                            .withUid(uid)
                            .withAllowed(true)
                            .build())
                    .build();
        }

        try {
            Pod pod = objectMapper.convertValue(request.getObject(), Pod.class);
            Map<String, String> annotations = pod.getMetadata().getAnnotations();
            
            if (annotations != null && "true".equalsIgnoreCase(annotations.get(INJECT_ANNOTATION))) {
                log.infof("Injecting Reshapr proxy into pod %s", pod.getMetadata().getName());
                
                // Clone the pod for diffing
                Pod mutatedPod = objectMapper.treeToValue(objectMapper.valueToTree(pod), Pod.class);
                
                // Check if container already exists
                boolean alreadyInjected = mutatedPod.getSpec().getContainers().stream()
                        .anyMatch(c -> PROXY_CONTAINER_NAME.equals(c.getName()));
                        
                if (!alreadyInjected) {
                    Container proxyContainer = new ContainerBuilder()
                            .withName(PROXY_CONTAINER_NAME)
                            .withImage(PROXY_IMAGE)
                            // Additional config such as env variables or ports can be added here
                            .build();
                            
                    mutatedPod.getSpec().getContainers().add(proxyContainer);
                    
                    // Generate JSON patch
                    String patch = generatePatch(pod, mutatedPod);
                    
                    return new AdmissionReviewBuilder()
                            .withResponse(new AdmissionResponseBuilder()
                                    .withUid(uid)
                                    .withAllowed(true)
                                    .withPatchType("JSONPatch")
                                    .withPatch(Base64.getEncoder().encodeToString(patch.getBytes(StandardCharsets.UTF_8)))
                                    .build())
                            .build();
                }
            }
            
            // Allow without mutations
            return new AdmissionReviewBuilder()
                    .withResponse(new AdmissionResponseBuilder()
                            .withUid(uid)
                            .withAllowed(true)
                            .build())
                    .build();
                    
        } catch (Exception e) {
            log.error("Failed to process admission request", e);
            return new AdmissionReviewBuilder()
                    .withResponse(new AdmissionResponseBuilder()
                            .withUid(uid)
                            .withAllowed(false)
                            .withNewStatus()
                                .withCode(500)
                                .withMessage(e.getMessage())
                            .endStatus()
                            .build())
                    .build();
        }
    }

    private String generatePatch(Pod originalPod, Pod mutatedPod) throws JsonProcessingException {
        var originalNode = objectMapper.valueToTree(originalPod);
        var mutatedNode = objectMapper.valueToTree(mutatedPod);
        var patchNode = JsonDiff.asJson(originalNode, mutatedNode);
        return objectMapper.writeValueAsString(patchNode);
    }
}
