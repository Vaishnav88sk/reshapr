/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.ctrl.service;

import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ArtifactType;
import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.Exposition;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.Operation;
import io.reshapr.ctrl.model.Secret;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.repository.ArtifactRepository;
import io.reshapr.ctrl.security.ReshaprTenantContext;
import io.reshapr.discovery.exposition.v1.ArtifactsRequest;
import io.reshapr.discovery.exposition.v1.ArtifactsResponse;
import io.reshapr.discovery.exposition.v1.ChangeType;
import io.reshapr.discovery.exposition.v1.ExpositionChangeEvent;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryRequest;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryResponse;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryServiceGrpc;
import io.reshapr.discovery.exposition.v1.ExpositionFetchRequest;

import io.grpc.Context;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.security.Authenticated;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * gRPC service handler for exposition discovery requests.
 * @author laurent
 */
@GrpcService
public class ExpositionDiscoveryServiceHandler extends ExpositionDiscoveryServiceGrpc.ExpositionDiscoveryServiceImplBase {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final Executor cancellationExecutor = Executors.newSingleThreadExecutor();

   /**
    * Map of organization ID to list of observers for exposition changes.
    * Why ConcurrentHashMap? See https://www.baeldung.com/java-synchronizedmap-vs-concurrenthashmap.
    */
   private final Map<String, List<OrganizationExpositionsObserver>> expositionChangeObservers = new ConcurrentHashMap<>();

   private final ConfigurationPlanManagerService configurationPlanManagerService;
   private final ArtifactRepository artifactRepository;

   /**
    * @param configurationPlanManagerService
    * @param artifactRepository
    */
   public ExpositionDiscoveryServiceHandler(ConfigurationPlanManagerService configurationPlanManagerService,
                                            ArtifactRepository artifactRepository) {
      this.configurationPlanManagerService = configurationPlanManagerService;
      this.artifactRepository = artifactRepository;

   }

   @Override
   @Authenticated
   @RunOnVirtualThread
   @ActivateRequestContext
   public void discoverExpositions(ExpositionDiscoveryRequest request, StreamObserver<ExpositionDiscoveryResponse> responseObserver) {
      logger.infof("Received ExpositionDiscoveryRequest for gatewayId: %s", request.getGatewayId());
      logger.tracef("Executing on thread: %s", Thread.currentThread().getName());

      ExpositionDiscoveryResponse.Builder builder = ExpositionDiscoveryResponse.newBuilder();

      List<ConfigurationPlan> configurations = configurationPlanManagerService.getExpositionConfigurations(request.getGatewayId(),
            request.getLabelsMap(), request.getFqdnsList());
      for (ConfigurationPlan configuration : configurations) {
         builder.addExpositions(grpcExpositionFromModel(configuration));
      }

      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
   }

   @Override
   @Authenticated
   @RunOnVirtualThread
   @ActivateRequestContext
   public void streamExpositionChanges(ExpositionDiscoveryRequest request, StreamObserver<ExpositionChangeEvent> responseObserver) {
      logger.infof("==> Subscribing to ExpositionChanges for gatewayId: %s", request.getGatewayId());
      logger.tracef("Executing on thread: %s", Thread.currentThread().getName());
      logger.tracef("Current tenant is '%s'", ReshaprTenantContext.getCurrentTenant());

      String organization = ReshaprTenantContext.getCurrentTenant();

      // Register the observer for the given tenant with gatewayId and labels.
      OrganizationExpositionsObserver observer = new OrganizationExpositionsObserver(request.getGatewayId(),
            request.getLabelsMap(), responseObserver);
      addOrganizationExpositionsObserver(organization, observer);

      List<OrganizationExpositionsObserver> observers = expositionChangeObservers.get(organization);
      logger.infof("# Current number of observers for organization '%s': %d", organization, observers.size());

      // Set a cancellation handler to remove the observer when the stream is cancelled.
      var serverCallStreamObserver = ((ServerCallStreamObserver<ExpositionChangeEvent>) responseObserver);
      serverCallStreamObserver.setOnCancelHandler(() -> {
         logger.infof("OnCancelHandler triggered for gatewayId: %s", request.getGatewayId());
         removeOrganizationExpositionsObserver(organization, request.getGatewayId());
      });

      // Set a Cancellation listener to remove the observer when the context is cancelled.
      Context.current().addListener(listener -> {;
         logger.infof("Cancellation listener triggered for gatewayId: %s", request.getGatewayId());
         removeOrganizationExpositionsObserver(organization, request.getGatewayId());
      }, cancellationExecutor);
   }

   private void addOrganizationExpositionsObserver(String organization, OrganizationExpositionsObserver observer) {
      // First clean up any existing observer for the same gatewayId.
      removeOrganizationExpositionsObserver(organization, observer.gatewayId);

      // Then add the new observer.
      logger.debugf("Adding new observer for organization: '%s', gatewayId: '%s'", organization, observer.gatewayId);
      List<OrganizationExpositionsObserver> registeredObservers = expositionChangeObservers.get(organization);
      registeredObservers.add(observer);
   }

   private void removeOrganizationExpositionsObserver(String organization, String gatewayId) {
      logger.debugf("Removing observer for organization: '%s', gatewayId: '%s'", organization, gatewayId);

      // Use a CopyOnWriteArrayList to avoid ConcurrentModificationException when mixing iteration and modification.
      List<OrganizationExpositionsObserver> registeredObservers = expositionChangeObservers.computeIfAbsent(organization, k -> new CopyOnWriteArrayList<>());
      // Build a list of observers to remove to batch the update of registeredObservers.
      List<OrganizationExpositionsObserver> observersToRemove = new ArrayList<>();
      for (OrganizationExpositionsObserver orgObserver : registeredObservers) {
         if (orgObserver.gatewayId.equals(gatewayId)) {
            logger.infof("<== Removing existing observer for gatewayId: %s", gatewayId);
            observersToRemove.add(orgObserver);
         }
      }
      if (!observersToRemove.isEmpty()) {
         registeredObservers.removeAll(observersToRemove);
      }
   }

   @Override
   @Authenticated
   @RunOnVirtualThread
   @ActivateRequestContext
   public void fetchExposition(ExpositionFetchRequest request, StreamObserver<io.reshapr.discovery.exposition.v1.Exposition> responseObserver) {
      logger.infof("Received ExpositionFetchRequest for gatewayId: %s", request.getExpositionId());
      logger.tracef("Executing on thread: %s", Thread.currentThread().getName());

      ConfigurationPlan configuration = configurationPlanManagerService.getExpositionConfiguration(request.getExpositionId());
      if (configuration != null) {
         io.reshapr.discovery.exposition.v1.Exposition grpcExposition = grpcExpositionFromModel(configuration);
         responseObserver.onNext(grpcExposition);
      } else {
         logger.warnf("No exposition found for ID: %s", request.getExpositionId());
         responseObserver.onError(new IllegalArgumentException("No exposition found for ID: " + request.getExpositionId()));
      }
      responseObserver.onCompleted();
   }

   @Override
   @Authenticated
   @RunOnVirtualThread
   @ActivateRequestContext
   public void fetchArtifacts(ArtifactsRequest request, StreamObserver<ArtifactsResponse> responseObserver) {
      logger.infof("Received ArtifactsRequest for serviceId: %s", request.getServiceId());
      logger.tracef("Executing on thread: %s", Thread.currentThread().getName());

      ArtifactsResponse.Builder builder = ArtifactsResponse.newBuilder()
            .setServiceId(request.getServiceId());

      List<Artifact> artifacts = artifactRepository.findByServiceId(request.getServiceId()).list();
      for (Artifact artifact : artifacts) {
         builder.addArtifacts(grpcArtifactFromModel(artifact));
      }

      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
   }

   /**
    * Clears the observer for the given organization and gatewayId.
    * @param organization The organization ID.
    * @param gatewayId The gateway ID.
    */
   public void clearObserver(String organization, String gatewayId) {
      removeOrganizationExpositionsObserver(organization, gatewayId);
   }

   /**
    * Notifies observers about the creation of a new exposition.
    * @param exposition The created exposition.
    * @param gatewayGroup The gateway group associated with the exposition.
    */
   public void notifyExpositionCreation(Exposition exposition, GatewayGroup gatewayGroup) {
      logger.debugf("Notifying ExpositionChangeEvent.CREATED for exposition '%s'", exposition.id);

      ExpositionChangeEvent event = ExpositionChangeEvent.newBuilder()
            .setExpositionId(exposition.id)
            .setChangeType(ChangeType.CREATED)
            .setExposition(grpcExpositionFromModel(exposition.configurationPlan))
            .build();
      notifyObservers(event, gatewayGroup);
   }

   /**
    * Notifies observers about the update of a new exposition.
    * @param exposition The created exposition.
    * @param gatewayGroup The gateway group associated with the exposition.
    */
   public void notifyExpositionUpdate(Exposition exposition, GatewayGroup gatewayGroup) {
      logger.debugf("Notifying ExpositionChangeEvent.UPDATED for exposition '%s'", exposition.id);

      ExpositionChangeEvent event = ExpositionChangeEvent.newBuilder()
            .setExpositionId(exposition.id)
            .setChangeType(ChangeType.UPDATED)
            .setExposition(grpcExpositionFromModel(exposition.configurationPlan))
            .build();
      notifyObservers(event, gatewayGroup);
   }

   /**
    * Notifies observers about the deletion of a new exposition.
    * @param exposition The created exposition.
    * @param gatewayGroup The gateway group associated with the exposition.
    */
   public void notifyExpositionDeletion(Exposition exposition, GatewayGroup gatewayGroup) {
      logger.debugf("Notifying ExpositionChangeEvent.DELETED for exposition '%s'", exposition.id);

      ExpositionChangeEvent event = ExpositionChangeEvent.newBuilder()
            .setExpositionId(exposition.id)
            .setChangeType(ChangeType.DELETED)
            .setExposition(grpcExpositionFromModel(exposition.configurationPlan))
            .build();
      notifyObservers(event, gatewayGroup);
   }

   private void notifyObservers(ExpositionChangeEvent event, GatewayGroup gatewayGroup) {
      List<OrganizationExpositionsObserver> observers = expositionChangeObservers.get(gatewayGroup.organizationId);
      if (observers != null) {
         observers.stream()
               .filter(observer -> gatewayGroup.labels != null
                     && gatewayGroup.labels.entrySet().containsAll(observer.gatewayLabels.entrySet()))
               .forEach(observer -> {
                  logger.debugf("=> Sending ExpositionChangeEvent to observer for gatewayId: %s", observer.gatewayId);
                  if (observer.observer != null) {
                     try {
                        observer.observer.onNext(event);
                     } catch (Exception e) {
                        logger.errorf("Error sending ExpositionChangeEvent to observer for gatewayId '%s'", observer.gatewayId);
                        observers.remove(observer);
                     }
                  }
               });
      } else {
         logger.warnf("No observers found for gatewayGroup: %s", gatewayGroup.id);
      }
   }

   private record OrganizationExpositionsObserver(String gatewayId, Map<String, String> gatewayLabels, StreamObserver<ExpositionChangeEvent> observer) {
   }

   private io.reshapr.discovery.exposition.v1.Exposition grpcExpositionFromModel(ConfigurationPlan configuration) {
      return io.reshapr.discovery.exposition.v1.Exposition.newBuilder()
            .setId(configuration.id)
            .setService(grpcServiceFromModel(configuration.service))
            .setConfiguration(grpcConfigurationFromModel(configuration))
            .build();
   }

   private io.reshapr.discovery.exposition.v1.Service grpcServiceFromModel(Service service) {
      return io.reshapr.discovery.exposition.v1.Service.newBuilder()
            .setId(service.id)
            .setOrganizationId(service.organizationId)
            .setName(service.name)
            .setVersion(service.version)
            .setType(service.type.name())
            .addAllOperations(grpcOperationsFromModel(service.operations))
            .build();
   }

   private List<io.reshapr.discovery.exposition.v1.Operation> grpcOperationsFromModel(List<Operation> operations) {
      if (operations == null || operations.isEmpty()) {
         return List.of();
      }
      return operations.stream()
            .map(operation -> {
               var opBuilder = io.reshapr.discovery.exposition.v1.Operation.newBuilder().setName(operation.name);
               // We have to deal with optional fields that way...
               if (operation.method != null) {
                  opBuilder.setMethod(operation.method);
               }
               if (operation.action != null) {
                  opBuilder.setAction(operation.action);
               }
               if (operation.inputName != null) {
                  opBuilder.setInputName(operation.inputName);
               }
               if (operation.outputName != null) {
                  opBuilder.setOutputName(operation.outputName);
               }
               return opBuilder.build();
            })
            .toList();
   }

   private io.reshapr.discovery.exposition.v1.Configuration grpcConfigurationFromModel(ConfigurationPlan configuration) {
       io.reshapr.discovery.exposition.v1.Configuration.Builder builder = io.reshapr.discovery.exposition.v1.Configuration.newBuilder()
            .setId(configuration.id)
            .setName(configuration.name)
            .setBackendEndpoint(configuration.backendEndpoint)
            .setBackendEndpointTimeout(configuration.backendEndpointTimeout != null ? configuration.backendEndpointTimeout : 3_000L)
            .addAllExcludedOperations(configuration.excludedOperations != null ? configuration.excludedOperations : List.of())
            .addAllIncludedOperations(configuration.includedOperations != null ? configuration.includedOperations : List.of());

      if (configuration.apiKey != null) {
         builder.setApiKey(configuration.apiKey);
      }
      if (configuration.oauth2Configuration != null) {
          io.reshapr.discovery.exposition.v1.OAuth2Configuration.Builder oauth2Builder = io.reshapr.discovery.exposition.v1.OAuth2Configuration.newBuilder()
               .addAllAuthorizationServers(configuration.oauth2Configuration.authorizationServers());
         if (configuration.oauth2Configuration.jwksUri() != null) {
            oauth2Builder.setJwksUri(configuration.oauth2Configuration.jwksUri());
         }
         if (configuration.oauth2Configuration.scopes() != null) {
            oauth2Builder.addAllScopes(configuration.oauth2Configuration.scopes());
         }
         builder.setOauth2Configuration(oauth2Builder.build());
      }
      if (configuration.backendSecret != null) {
         builder.setBackendSecret(grpcSecretFromModel(configuration.backendSecret));
      }
      return builder.build();
   }

   private io.reshapr.discovery.exposition.v1.Secret grpcSecretFromModel(Secret secret) {
       io.reshapr.discovery.exposition.v1.Secret.Builder builder = io.reshapr.discovery.exposition.v1.Secret.newBuilder()
            .setName(secret.name);
      if (secret.username != null) {
         builder.setUsername(secret.username);
      }
      if (secret.getPassword() != null) {
         builder.setPassword(secret.getPassword());
      }
      if (secret.getToken() != null) {
         builder.setToken(secret.getToken());
      }
       if (secret.tokenHeader != null) {
          builder.setTokenHeader(secret.tokenHeader);
       }
       if (secret.certPem != null) {
          builder.setCertPem(secret.certPem);
       }
       builder.setUseElicitation(secret.useElicitation);
       // Take care of third party OAuth2 configuration is elicitation is used.
       if (secret.useElicitation && secret.oauth2ClientConfiguration != null) {
           io.reshapr.discovery.exposition.v1.OAuth2ClientConfiguration.Builder tpOauth2Builder =
                   io.reshapr.discovery.exposition.v1.OAuth2ClientConfiguration.newBuilder();
            var oauth2ClientConfiguration = secret.oauth2ClientConfiguration;
            if (oauth2ClientConfiguration.clientId() != null) {
               tpOauth2Builder.setClientId(oauth2ClientConfiguration.clientId());
            }
            if (oauth2ClientConfiguration.authorizationEndpoint() != null) {
               tpOauth2Builder.setAuthorizationEndpoint(oauth2ClientConfiguration.authorizationEndpoint());
            }
            if (oauth2ClientConfiguration.tokenEndpoint() != null) {
               tpOauth2Builder.setTokenEndpoint(oauth2ClientConfiguration.tokenEndpoint());
            }
            builder.setOauth2ClientConfiguration(tpOauth2Builder.build());
       }
       return builder.build();
   }

   private io.reshapr.discovery.exposition.v1.Artifact grpcArtifactFromModel(Artifact artifact) {
       io.reshapr.discovery.exposition.v1.Artifact.Builder builder =
               io.reshapr.discovery.exposition.v1.Artifact.newBuilder()
                  .setId(artifact.id)
                  .setName(artifact.name)
                  .setType(grpcArtifactTypeFromModel(artifact.type))
                  .setContent(artifact.content)
                  .setMainArtifact(artifact.mainArtifact);
      if (artifact.path != null) {
         builder.setPath(artifact.path);
      }
      return builder.build();
   }

   private io.reshapr.discovery.exposition.v1.ArtifactType grpcArtifactTypeFromModel(ArtifactType type) {
      return switch (type) {
         case OPEN_API_SPEC -> io.reshapr.discovery.exposition.v1.ArtifactType.OPEN_API_SPEC;
         case GRAPHQL_SCHEMA -> io.reshapr.discovery.exposition.v1.ArtifactType.GRAPHQL_SCHEMA;
         case PROTOBUF_SCHEMA -> io.reshapr.discovery.exposition.v1.ArtifactType.PROTOBUF_SCHEMA;
         case PROTOBUF_DESCRIPTOR -> io.reshapr.discovery.exposition.v1.ArtifactType.PROTOBUF_DESCRIPTOR;
         case JSON_SCHEMA -> io.reshapr.discovery.exposition.v1.ArtifactType.JSON_SCHEMA;
         case RESHAPR_PROMPTS -> io.reshapr.discovery.exposition.v1.ArtifactType.RESHAPR_PROMPTS;
         case RESHAPR_CUSTOM_TOOLS -> io.reshapr.discovery.exposition.v1.ArtifactType.RESHAPR_CUSTOM_TOOLS;
         case RESHAPR_RESOURCES -> io.reshapr.discovery.exposition.v1.ArtifactType.RESHAPR_RESOURCES;
         default -> io.reshapr.discovery.exposition.v1.ArtifactType.JSON_FRAGMENT; // Default to JSON_FRAGMENT if unknown
      };
   }
}
