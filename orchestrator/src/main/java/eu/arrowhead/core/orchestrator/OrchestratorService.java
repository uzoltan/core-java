package eu.arrowhead.core.orchestrator;

import eu.arrowhead.common.Utility;
import eu.arrowhead.common.database.OrchestrationStore;
import eu.arrowhead.common.exception.BadPayloadException;
import eu.arrowhead.common.exception.DataNotFoundException;
import eu.arrowhead.common.model.ArrowheadCloud;
import eu.arrowhead.common.model.ArrowheadService;
import eu.arrowhead.common.model.ArrowheadSystem;
import eu.arrowhead.common.model.messages.GSDAnswer;
import eu.arrowhead.common.model.messages.GSDRequestForm;
import eu.arrowhead.common.model.messages.GSDResult;
import eu.arrowhead.common.model.messages.ICNRequestForm;
import eu.arrowhead.common.model.messages.ICNResult;
import eu.arrowhead.common.model.messages.IntraCloudAuthRequest;
import eu.arrowhead.common.model.messages.IntraCloudAuthResponse;
import eu.arrowhead.common.model.messages.OrchestrationForm;
import eu.arrowhead.common.model.messages.OrchestrationResponse;
import eu.arrowhead.common.model.messages.ProvidedService;
import eu.arrowhead.common.model.messages.QoSReservationResponse;
import eu.arrowhead.common.model.messages.QoSReserve;
import eu.arrowhead.common.model.messages.QoSVerificationResponse;
import eu.arrowhead.common.model.messages.QoSVerify;
import eu.arrowhead.common.model.messages.ServiceQueryForm;
import eu.arrowhead.common.model.messages.ServiceQueryResult;
import eu.arrowhead.common.model.messages.ServiceRequestForm;
import eu.arrowhead.common.model.messages.TokenGenerationRequest;
import eu.arrowhead.common.model.messages.TokenGenerationResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author umlaufz
 */
final class OrchestratorService {

  //TODO update logs, and mostly only use them at the end of methods
  private static Logger log = Logger.getLogger(OrchestratorService.class.getName());

  private OrchestratorService() throws AssertionError {
    throw new AssertionError("OrchestratorService is a non-instantiable class");
  }

  /**
   * This method represents the regular orchestration process where the requester System is in the local Cloud. In this process the Orchestration
   * Store is ignored, and the Orchestrator first tries to find a provider in the local Cloud. If that fails but the enableInterCloud flag is set to
   * true, the Orchestrator tries to find a provider in other Clouds.
   *
   * @return OrchestrationResponse
   *
   * @throws BadPayloadException, DataNotFoundException
   */
  static OrchestrationResponse dynamicOrchestration(ServiceRequestForm srf) {
    log.info("Entered the regularOrchestration method.");
    Map<String, Boolean> orchestrationFlags = srf.getOrchestrationFlags();

    try {
      // Querying the Service Registry
      List<ProvidedService> psList = new ArrayList<>();
      psList = queryServiceRegistry(srf.getRequestedService(), orchestrationFlags.get("metadataSearch"), orchestrationFlags.get("pingProviders"));

      // Cross-checking the SR response with the Authorization
      List<ArrowheadSystem> providerSystems = new ArrayList<>();
      for (ProvidedService service : psList) {
        providerSystems.add(service.getProvider());
      }
      providerSystems = queryAuthorization(srf.getRequesterSystem(), srf.getRequestedService(), providerSystems);

			/*
       * The Authorization check only returns the provider systems where
			 * the requester system is authorized to consume the service. We
			 * filter out the non-authorized systems from the SR response.
			 */
      List<ProvidedService> temp = new ArrayList<>();
      for (ProvidedService service : psList) {
        if (!providerSystems.contains(service.getProvider())) {
          temp.add(service);
        }
      }
      psList.removeAll(temp);

      // If needed, removing the non-preferred providers from the
      // remaining list
      if (orchestrationFlags.get("onlyPreferred")) {
        psList = removeNonPreferred(psList, srf.getPreferredProviders());
      }

      //TODO ide jön majd a qosVerification
			/*
       * If matchmaking is requested, we pick out 1 ProvidedService entity
			 * from the list. If only preferred Providers are allowed,
			 * matchmaking might not be possible.
			 */
      if (orchestrationFlags.get("matchmaking")) {
        ProvidedService ps = intraCloudMatchmaking(psList, orchestrationFlags.get("onlyPreferred"), srf.getPreferredProviders(),
                                                   srf.getPreferredClouds().size());
        psList.clear();
        psList.add(ps);
      }

      if (!srf.getRequestedQoS().isEmpty()) {
        psList = fancyQosMethodName(srf, psList);
      }

      //TODO qosreserve
      // All the filtering is done, need to compile the response
      return compileOrchestrationResponse(psList, true, srf);
    } /*
       * If the Intra-Cloud orchestration fails somewhere (SR, Auth,
			 * filtering, matchmaking) we catch the exception, because
			 * Inter-Cloud orchestration might be allowed. If not, we throw the
			 * same exception again.
			 */ catch (DataNotFoundException ex) {
      if (!orchestrationFlags.get("enableInterCloud")) {
        log.info("Intra-Cloud orchestration failed with DNFException, but Inter-Cloud is not allowed.");
        throw new DataNotFoundException(ex.getMessage());
      }
    } catch (BadPayloadException ex) { //TODO why is this branch needed???? check it in what case we wanna go on if this is thrown somewhere
      if (!orchestrationFlags.get("enableInterCloud")) {
        log.info("Intra-Cloud orchestration failed with BPException, but Inter-Cloud is not allowed.");
        throw new BadPayloadException(ex.getMessage());
      }
    }
    /*
     * If the code reaches this part, that means the Intra-Cloud
		 * orchestration failed, but the Inter-Cloud orchestration is allowed,
		 * so we try that too.
		 */

    return triggerInterCloud(srf);
  }

  /**
   * This method represents the orchestration process where the Orchestration Store database is used to see if there is a provider for the requester
   * System. The Orchestration Store contains preset orchestration information, which should not change in runtime.
   *
   * @return OrchestrationResponse
   */
  static OrchestrationResponse orchestrationFromStore(ServiceRequestForm srf) {
    log.info("Entered the orchestrationFromStore method.");
    Map<String, Boolean> orchestrationFlags = srf.getOrchestrationFlags();
    // Querying the Orchestration Store for matching entries
    List<OrchestrationStore> entryList = queryOrchestrationStore(srf.getRequesterSystem(), srf.getRequestedService());

    // Priority list based store orchestration
    List<OrchestrationStore> intraStoreList = new ArrayList<>();
    for (OrchestrationStore entry : entryList) {
      if (entry.getProviderCloud() == null) {
        intraStoreList.add(entry);
      }
    }

		/*
     * Before we iterate through the entry list to pick out a provider, we
		 * have to poll the Service Registry and Authorization Systems, so we
		 * have these 2 other ArrowheadSystem provider lists to cross-check the
		 * entry list with.
		 */
    List<ProvidedService> psList;
    List<ArrowheadSystem> serviceProviders = new ArrayList<>();
    List<ArrowheadSystem> intraProviders = new ArrayList<>();
    List<ArrowheadSystem> authorizedIntraProviders = new ArrayList<>();
    try {
      // Querying the Service Registry with the intra-cloud Store entries
      psList = queryServiceRegistry(srf.getRequestedService(), orchestrationFlags.get("metadataSearch"), orchestrationFlags.get("pingProviders"));

      // Compile the list of providers which are in the Service Registry
      for (ProvidedService ps : psList) {
        serviceProviders.add(ps.getProvider());
      }

			/*
       * If the Store entry did not had a providerCloud, it must have had
			 * a providerSystem. We have to query the Authorization System with
			 * these providers.
			 */
      for (OrchestrationStore entry : intraStoreList) {
        intraProviders.add(entry.getProviderSystem());
      }

      // Querying the Authorization System
      authorizedIntraProviders = queryAuthorization(srf.getRequesterSystem(), srf.getRequestedService(), intraProviders);

      //TODO do qosVerification here
    } /*
       * If the SR or Authorization query throws DNFException, we have to
			 * catch it, because the inter-cloud store entries can still be
			 * viable options.
			 */ catch (DataNotFoundException ex) {
    }

    // Checking for viable providers in the Store entry list
    for (OrchestrationStore entry : entryList) {
      // If the entry does not have a provider Cloud then it is an
      // intra-cloud entry.
      if (entry.getProviderCloud() == null) {
        /*
         * Both of the provider lists (from SR and Auth query) need to
				 * contain the provider of the Store entry. We return with a
				 * provider if it fills this requirement. (Store orchestration
				 * will always only return 1 provider.)
				 */
        if (serviceProviders.contains(entry.getProviderSystem()) && authorizedIntraProviders.contains(entry.getProviderSystem())) {
          //TODO ez mé
          List<OrchestrationStore> tempList = new ArrayList<>(Arrays.asList(entry));
          //TODO qosReserve itten
          return compileOrchestrationResponseFromStore(tempList, orchestrationFlags.get("generateToken"));
        }
      } /*
         * Inter-Cloud store entries must be handlend inside the for
				 * loop, since every provider Cloud means a different ICN
				 * process.
				 */ else {
        try {
          /*
           * Setting up the SRF for the compileICNRequestForm method.
					 * In case of orchestrationFromStore the preferences are the
					 * stored Cloud (and System), and not what is inside the SRF
					 * payload. (Should be null when requesting Store
					 * orchestration)
					 */
          List<ArrowheadCloud> providerCloud = new ArrayList<>(Arrays.asList(entry.getProviderCloud()));
          srf.setPreferredClouds(providerCloud);
          if (entry.getProviderSystem() != null) {
            List<ArrowheadSystem> providerSystem = new ArrayList<>(Arrays.asList(entry.getProviderSystem()));
            srf.setPreferredProviders(providerSystem);
          } else {
            srf.setPreferredProviders(null);
          }

          ICNRequestForm icnRequestForm = compileICNRequestForm(srf, entry.getProviderCloud());
          ICNResult icnResult = startICN(icnRequestForm);
          // Use matchmaking on the ICN result. (Store orchestration
          // will always only return 1 provider.)
          return icnMatchmaking(icnResult, entry);
        } /*
           * If the ICN process failed on this store entry, we catch
					 * the exception and go to the next Store entry in the foor
					 * loop.
					 */ catch (DataNotFoundException ex) {
        }
      }
    }

    // If the foor loop finished but we still could not return a result, we
    // throw a DNFException.
    throw new DataNotFoundException("OrchestrationFromStore failed.");
  }

  /**
   * This method represents the orchestration process where the requester System only asked for Inter-Cloud servicing.
   *
   * @return OrchestrationResponse
   */
  static OrchestrationResponse triggerInterCloud(ServiceRequestForm srf) {
    log.info("Entered the triggerInterCloud method.");

    Map<String, Boolean> orchestrationFlags = new HashMap<>();
    orchestrationFlags = srf.getOrchestrationFlags();

    // Telling the Gatekeeper to do a Global Service Discovery
    GSDResult result = startGSD(srf.getRequestedService(), srf.getPreferredClouds());

    // Picking a target Cloud from the ones that responded to the GSD poll
    ArrowheadCloud targetCloud = interCloudMatchmaking(result, srf.getPreferredClouds(), orchestrationFlags.get("onlyPreferred"));

    // Telling the Gatekeeper to start the Inter-Cloud Negotiations process
    ICNRequestForm icnRequestForm = compileICNRequestForm(srf, targetCloud);
    ICNResult icnResult = startICN(icnRequestForm);

    // If matchmaking is requested, we pick one provider from the ICN result
    if (orchestrationFlags.get("matchmaking")) {
      return icnMatchmaking(icnResult, icnRequestForm.getPreferredProviders());
    } else {
      return icnResult.getInstructions();
    }
  }

  /**
   * This method represents the orchestration process where the requester System is NOT in the local Cloud. This means that the Gatekeeper made sure
   * that this request from the remote Orchestrator can be satisfied in this Cloud. (Gatekeeper polled the Service Registry and Authorization
   * Systems.)
   *
   * @return OrchestrationResponse
   */
  static OrchestrationResponse externalServiceRequest(ServiceRequestForm srf) {
    log.info("Entered the externalServiceRequest method.");
    Map<String, Boolean> orchestrationFlags = srf.getOrchestrationFlags();

    // Querying the Service Registry to get the list of Provider Systems
    List<ProvidedService> psList = queryServiceRegistry(srf.getRequestedService(), orchestrationFlags.get("metadataSearch"), orchestrationFlags.get("pingProviders"));

		/*
     * If needed, removing the non-preferred providers from the SR response.
		 * (If needed, matchmaking is done at the request sender Cloud.)
		 */
    if (orchestrationFlags.get("onlyPreferred")) {
      log.info("Only preferred matchmaking is requested.");
      psList = removeNonPreferred(psList, srf.getPreferredProviders());
    }

    // Compiling the orchestration response
    return compileOrchestrationResponse(psList, true, srf);
  }

  /**
   * This method queries the Service Registry core system for a specific ArrowheadService. The returned list consists of possible service providers.
   *
   * @return List<ProvidedService>
   */
  private static List<ProvidedService> queryServiceRegistry(ArrowheadService service, boolean metadataSearch, boolean pingProviders) {
    log.info("Entered the queryServiceRegistry method.");

    // Compiling the URI and the request payload
    String srURI = UriBuilder.fromPath(Utility.getServiceRegistryUri()).path(service.getServiceGroup()).path(service.getServiceDefinition())
        .toString();
    String tsig_key = Utility.getCoreSystem("serviceregistry").getAuthenticationInfo();
    ServiceQueryForm queryForm = new ServiceQueryForm(service.getServiceMetadata(), service.getInterfaces(), pingProviders, metadataSearch, tsig_key);

    // Sending the request, parsing the returned result
    log.info("Querying ServiceRegistry for requested Service: " + service.toString());
    // TODO if SR secured, send SSLContext
    Response srResponse = Utility.sendRequest(srURI, "PUT", queryForm);
    ServiceQueryResult serviceQueryResult = srResponse.readEntity(ServiceQueryResult.class);
    if (serviceQueryResult == null) {
      log.info("ServiceRegistry query came back empty. " + "(OrchestratorService:queryServiceRegistry DataNotFoundException)");
      throw new DataNotFoundException(
          "ServiceRegistry query came back empty for " + service.toString() + " (Interfaces field for service can not be empty)");
    }
    // If there are non-valid entries in the Service Registry response, we filter those out
    List<ProvidedService> temp = new ArrayList<>();
    for (ProvidedService ps : serviceQueryResult.getServiceQueryData()) {
      if (!ps.isValid()) {
        temp.add(ps);
      }
    }
    serviceQueryResult.getServiceQueryData().removeAll(temp);

    if (serviceQueryResult.isPayloadEmpty()) {
      log.info("ServiceRegistry query came back empty. " + "(OrchestratorService:queryServiceRegistry DataNotFoundException)");
      throw new DataNotFoundException("ServiceRegistry query came back empty for service " + service.toString());
    }
    log.info("ServiceRegistry query successful. Number of providers: " + serviceQueryResult.getServiceQueryData().size());

    return serviceQueryResult.getServiceQueryData();
  }

  /**
   * This method queries the Authorization core system with a consumer/service/providerList triplet. The returned list only contains the authorized
   * providers.
   *
   * @return List<ArrowheadSystem>
   */
  private static List<ArrowheadSystem> queryAuthorization(ArrowheadSystem consumer, ArrowheadService service, List<ArrowheadSystem> providerList) {
    log.info("Entered the queryAuthorization method.");

    // Compiling the URI and the request payload
    String URI = UriBuilder.fromPath(Utility.getAuthorizationUri()).path("intracloud").toString();
    IntraCloudAuthRequest request = new IntraCloudAuthRequest(consumer, providerList, service, false);
    log.info("Intra-Cloud authorization request ready to send to: " + URI);

    // Extracting the useful payload from the response, sending back the
    // authorized Systems
    Response response = Utility.sendRequest(URI, "PUT", request);
    IntraCloudAuthResponse authResponse = response.readEntity(IntraCloudAuthResponse.class);
    List<ArrowheadSystem> authorizedSystems = new ArrayList<>();
    //Set view of HashMap ensures there are no duplicates between the keys (systems)
    for (Map.Entry<ArrowheadSystem, Boolean> entry : authResponse.getAuthorizationMap().entrySet()) {
      if (entry.getValue()) {
        authorizedSystems.add(entry.getKey());
      }
    }

    // Throwing exception if none of the providers are authorized for this
    // consumer/service pair.
    if (authorizedSystems.isEmpty()) {
      log.info("OrchestratorService:queryAuthorization throws DataNotFoundException");
      throw new DataNotFoundException("The consumer system is not authorized to receive servicing " + "from any of the provider systems.");
    }

    log.info("Authorization query is done, sending back the authorized Systems. " + authorizedSystems.size());
    return authorizedSystems;
  }

  /**
   * Intra-Cloud matchmaking method. As the last step of the local orchestration process (if requested) we pick out 1 provider from the remaining
   * list. Providers preferred by the consumer have higher priority. Custom matchmaking algorithm can be implemented, as of now it just returns the
   * first provider from the list.
   *
   * @return ProvidedService
   */
  //TODO ez a függvény a removeNonPreferred dolgát is megcsinálja?
  private static ProvidedService intraCloudMatchmaking(List<ProvidedService> psList, boolean onlyPreferred, List<ArrowheadSystem> preferredList,
                                                       int notLocalSystems) {
    log.info("Entered the intraCloudMatchmaking method. psList size: " + psList.size());

    if (psList.isEmpty()) {
      log.info("IntraCloudMatchmaking received an empty ProvidedService list. " + "(OrchestratorService:intraCloudMatchmaking BadPayloadException)");
      throw new BadPayloadException("ProvidedService list is empty, Intra-Cloud matchmaking is " + "not possible in the Orchestration process.");
    }

    // We delete all the preferredProviders from the list which belong to a
    // remote cloud
    preferredList.subList(0, notLocalSystems).clear();
    log.info(notLocalSystems + " not local Systems deleted from the preferred list. " + "Remaining providers: " + preferredList.size());

    // First we try to return with a preferred provider
    if (!preferredList.isEmpty()) {
      /*
       * We iterate through both ArrowheadSystem list, and return with the
			 * proper ProvidedService object if we find a match.
			 */
      for (ArrowheadSystem system : preferredList) {
        for (ProvidedService ps : psList) {
          if (system.equals(ps.getProvider())) {
            log.info("Preferred local System found in the list of ProvidedServices. " + "Intra-Cloud matchmaking finished.");
            return ps;
          }
        }
      }

      // No match found, return the first ProvidedService entry if it is
      // allowed.
      if (onlyPreferred) {
        log.info("No preferred local System found in the list of ProvidedServices. " + "Intra-Cloud matchmaking failed.");
        throw new DataNotFoundException("No preferred local System found in the " + "list of ProvidedServices. Intra-Cloud matchmaking failed");
      } else {
        // Implement custom matchmaking algorithm here
        log.info("No preferred local System found in the list of ProvidedServices. " + "Returning the first ProvidedService entry.");
        return psList.get(0);
      }
    } else if (onlyPreferred) {
      log.info("Bad request sent to the IntraCloudMatchmaking.");
      throw new BadPayloadException(
          "Bad request sent to the Intra-Cloud matchmaking." + "(onlyPreferred flag is true, but no local preferredProviders)");
    } else {
      /*
       * If there are no preferences we return with the first possible
			 * choice by default. Custom matchmaking algorithm can be
			 * implemented here.
			 */
      log.info("No preferred providers were given, returning the first ProvidedService entry.");
      return psList.get(0);
    }
  }

  /**
   * This method filters out all the entries of the given ProvidedService list, which does not have a preferred provider.
   *
   * @return List<ProvidedService>
   */
  private static List<ProvidedService> removeNonPreferred(List<ProvidedService> psList, List<ArrowheadSystem> preferredProviders) {
    log.info("Entered the removeNonPreferred method.");

    if (psList.isEmpty() || preferredProviders.isEmpty()) {
      log.info("OrchestratorService:removeNonPreferred BadPayloadException");
      throw new BadPayloadException(
          "ProvidedService or PreferredProviders list is empty. " + "(OrchestrationService:removeNonPreferred BadPayloadException)");
    }

    List<ProvidedService> preferredList = new ArrayList<>();
    for (ArrowheadSystem system : preferredProviders) {
      for (ProvidedService ps : psList) {
        if (system.equals(ps.getProvider())) {
          preferredList.add(ps);
        }
      }
    }

    if (preferredList.isEmpty()) {
      log.info("OrchestratorService:removeNonPreferred DataNotFoundException");
      throw new DataNotFoundException("No preferred local System found in the the list of provider Systems. "
                                          + "(OrchestrationService:removeNonPreferred DataNotFoundException)");
    }

    log.info("removeNonPreferred returns with " + preferredList.size() + " ProvidedServices.");
    return preferredList;
  }

  /**
   * This method initiates the GSD process by sending a request to the Gatekeeper core system.
   *
   * @return GSDResult
   */
  private static GSDResult startGSD(ArrowheadService requestedService, List<ArrowheadCloud> preferredClouds) {
    log.info("Entered the startGSD method.");

    // Compiling the URI and the request payload
    String URI = Utility.getGatekeeperUri();
    URI = UriBuilder.fromPath(URI).path("init_gsd").toString();
    GSDRequestForm requestForm = new GSDRequestForm(requestedService, preferredClouds);

    // Sending the request, do sanity check on the returned result
    Response response = Utility.sendRequest(URI, "PUT", requestForm);
    GSDResult result = response.readEntity(GSDResult.class);
    if (result == null || !result.isPayloadUsable()) {
      log.info("GlobalServiceDiscovery yielded no result. " + "(OrchestratorService:startGSD DataNotFoundException)");
      throw new DataNotFoundException("GlobalServiceDiscovery yielded no result.");
    }

    log.info(result.getResponse().size() + " gatekeeper(s) answered to the GSD poll.");
    return result;
  }

  /**
   * Inter-Cloud matchmaking is mandaroty for picking out a target Cloud to do ICN with. Clouds preferred by the consumer have higher priority. Custom
   * matchmaking algorithm can be implemented, as of now it just returns the first Cloud from the list.
   *
   * @return ArrowheadCloud
   */
  private static ArrowheadCloud interCloudMatchmaking(GSDResult result, List<ArrowheadCloud> preferredClouds, boolean onlyPreferred) {
    log.info("Entered the interCloudMatchmaking method.");

    // Extracting the valid ArrowheadClouds from the GSDResult
    List<ArrowheadCloud> partnerClouds = new ArrayList<>();
    for (GSDAnswer answer : result.getResponse()) {
      if (answer.getProviderCloud().isValid()) {
        partnerClouds.add(answer.getProviderCloud());
      }
    }

    // Using a set to remove duplicate entries from the preferredClouds list
    Set<ArrowheadCloud> prefClouds = new LinkedHashSet<>(preferredClouds);
    log.info("Number of partner Clouds from GSD: " + partnerClouds.size() + ", number of preferred Clouds from SRF: " + prefClouds.size());

    if (!prefClouds.isEmpty()) {
      // We iterate through both ArrowheadCloud list, and return with 1 if
      // we find a match.
      for (ArrowheadCloud preferredCloud : prefClouds) {
        for (ArrowheadCloud partnerCloud : partnerClouds) {
          if (preferredCloud.equals(partnerCloud)) {
            log.info("Preferred Cloud found in the GSD response. Inter-Cloud matchmaking finished.");
            return partnerCloud;
          }
        }
      }

      // No match found, return the first ArrowheadCloud from the
      // GSDResult if it is allowed.
      if (onlyPreferred) {
        log.info("No preferred Cloud found in the GSD response. Inter-Cloud matchmaking failed.");
        throw new DataNotFoundException("No preferred Cloud found in the GSD response. Inter-Cloud matchmaking failed.");
      } else {
        // Implement custom matchmaking algorithm here
        log.info("No preferred Cloud found in the partner Clouds. Returning the first ProvidedService entry.");
        return partnerClouds.get(0);
      }
    } else if (onlyPreferred) {
      log.info("Bad request sent to the InterCloudMatchmaking.");
      throw new BadPayloadException("Bad request sent to the Inter-Cloud matchmaking. (onlyPreferred flag is true, but no preferredClouds)");
    } else {
      /*
       * If there are no preferences we return with the first possible
			 * choice by default. Custom matchmaking algorithm can be
			 * implemented here.
			 */
      log.info("No preferred Clouds were given, returning the first partner Cloud entry.");
      return partnerClouds.get(0);
    }
  }

  /**
   * From the given parameteres this method compiles an ICNRequestForm to start the ICN process.
   *
   * @return ICNRequestForm
   */
  private static ICNRequestForm compileICNRequestForm(ServiceRequestForm srf, ArrowheadCloud targetCloud) {
    log.info("Entered the compileICNRequestForm method.");

    List<ArrowheadSystem> preferredProviders = new ArrayList<>();
    if (srf.getPreferredProviders() == null || srf.getPreferredProviders().size() == 0) {
      log.info("No preferredProviders were given, sending ICNRequestForm without it.");
    } else {
      // Getting the preferred Providers which belong to the preferred
      // Cloud
      for (int i = 0; i < srf.getPreferredClouds().size(); i++) {
        if (srf.getPreferredClouds().get(i).equals(targetCloud)) {
          // We might have a preferred Cloud but no preferred Provider
          // inside the Cloud
          if (srf.getPreferredProviders().size() > i && srf.getPreferredProviders().get(i) != null && srf.getPreferredProviders().get(i).isValid()) {
            preferredProviders.add(srf.getPreferredProviders().get(i));
          }
        }
      }
      log.info(preferredProviders.size() + " preferred providers selected for this Cloud.");
    }

    // Compiling the payload
    Map<String, Boolean> negotiationFlags = new HashMap<>();
    negotiationFlags.put("metadataSearch", srf.getOrchestrationFlags().get("metadataSearch"));
    negotiationFlags.put("pingProviders", srf.getOrchestrationFlags().get("pingProviders"));
    negotiationFlags.put("onlyPreferred", srf.getOrchestrationFlags().get("onlyPreferred"));
    negotiationFlags.put("generateToken", srf.getOrchestrationFlags().get("generateToken"));

    return new ICNRequestForm(srf.getRequestedService(), null, targetCloud, srf.getRequesterSystem(), preferredProviders,
                              negotiationFlags);
  }

  /**
   * This method initiates the ICN process by sending a request to the Gatekeeper core system.
   *
   * @return ICNResult
   */
  private static ICNResult startICN(ICNRequestForm requestForm) {
    log.info("Entered the startICN method.");

    // Compiling the URI, sending the request, do sanity check on the
    // returned result
    String URI = Utility.getGatekeeperUri();
    URI = UriBuilder.fromPath(URI).path("init_icn").toString();
    Response response = Utility.sendRequest(URI, "PUT", requestForm);
    ICNResult result = response.readEntity(ICNResult.class);
    if (!result.isPayloadUsable()) {
      log.info("ICN yielded no result. (OrchestratorService:startICN DataNotFoundException)");
      throw new DataNotFoundException("ICN yielded no result.");
    }

    log.info(result.getInstructions().getResponse().size() + " possible providers in the ICN result.");
    return result;
  }

  /**
   * Matchmaking method for ICN results. As the last step of the inter-cloud orchestration process (if requested) we pick out 1 provider from the ICN
   * result list. Providers preferred by the consumer have higher priority. Custom matchmaking algorithm can be implemented, as of now it just returns
   * the first provider from the list.
   *
   * @return OrchestrationResponse
   */
  private static OrchestrationResponse icnMatchmaking(ICNResult icnResult, List<ArrowheadSystem> preferredProviders) {
    log.info("Entered the (first) icnMatchmaking method.");

		/*
     * We first try to find a match between preferredProviders and the
		 * received providers from the ICN result.
		 */
    List<OrchestrationForm> ofList = new ArrayList<>();
    if (preferredProviders != null && !preferredProviders.isEmpty()) {
      for (ArrowheadSystem preferredProvider : preferredProviders) {
        for (OrchestrationForm of : icnResult.getInstructions().getResponse()) {
          if (preferredProvider.equals(of.getProvider())) {
            ofList.add(of);
            icnResult.getInstructions().setResponse(ofList);
            log.info("Preferred provider System found in the ICNResult, " + "ICN matchmaking finished.");
            return icnResult.getInstructions();
          }
        }
      }
    }

    // If that fails, we just select the first OrchestrationForm
    // Implement custom matchmaking algorithm here
    ofList.add(icnResult.getInstructions().getResponse().get(0));
    icnResult.getInstructions().setResponse(ofList);
    log.info("No preferred provider System was found in the ICNResult, " + "returning the first OrchestrationForm entry.");
    return icnResult.getInstructions();
  }

  /**
   * Matchmaking method for ICN results. This version of the method is used by the orchestrationFromStore method. The method searches for the provider
   * (which was given in the Store entry) in the ICN result.
   *
   * @return OrchestrationResponse
   */
  private static OrchestrationResponse icnMatchmaking(ICNResult icnResult, OrchestrationStore entry) {
    log.info("Entered the (second) icnMatchmaking method.");

    List<OrchestrationForm> ofList = new ArrayList<>();
    for (OrchestrationForm of : icnResult.getInstructions().getResponse()) {
      if (entry.getProviderSystem().equals(of.getProvider())) {
        ofList.add(of);
        icnResult.getInstructions().setResponse(ofList);
        log.info("Preferred provider System found in the ICNResult, " + "ICN matchmaking finished.");
        return icnResult.getInstructions();
      }
    }

    log.info("Second icnMatchmaking method throws DataNotFoundException");
    throw new DataNotFoundException("The given provider in the Store " + "entry was not found in the ICN result.");
  }

  /**
   * @return List<OrchestrationStore>
   */
  private static List<OrchestrationStore> queryOrchestrationStore(@NotNull ArrowheadSystem consumer, @Nullable ArrowheadService service) {
    List<OrchestrationStore> retrievedList;

    //If the service is null, we return all the default store entries.
    if (service == null) {
      retrievedList = StoreService.getDefaultStoreEntries(consumer);
    }
    //If not, we return all the Orchestration Store entries specified by the consumer and the service.
    else {
      retrievedList = StoreService.getStoreEntries(consumer, service);
    }

    if (!retrievedList.isEmpty()) {
      Collections.sort(retrievedList);
      return retrievedList;
    } else {
      throw new DataNotFoundException("No Orchestration Store entries were found for consumer " + consumer.toString());
    }
  }

  /**
   * This method compiles the OrchestrationResponse object. The regularOrchestration and externalServiceRequest processes use this version of the
   * method. (The triggerInterCloud method gets back the same response from an externalServiceRequest at a remote Cloud.)
   *
   * @return OrchestrationResponse
   */
  //TODO generate token megszüntetése, srf.service metadata (security - token ) alapján kell generálásról dönteni
  private static OrchestrationResponse compileOrchestrationResponse(List<ProvidedService> psList, boolean generateToken, ServiceRequestForm srf) {
    log.info("Entered the (first) compileOrchestrationResponse method.");

    List<OrchestrationForm> ofList = new ArrayList<>();
    List<ArrowheadSystem> providerList = new ArrayList<>();

    for (ProvidedService ps : psList) {
      providerList.add(ps.getProvider());
    }

    TokenGenerationResponse tokenResponse = null;
    if (generateToken) {
      String authURI = Utility.getAuthorizationUri();
      authURI = UriBuilder.fromPath(authURI).path("token").toString();
      TokenGenerationRequest tokenRequest = new TokenGenerationRequest(srf.getRequesterSystem(), srf.getRequesterCloud(), providerList,
                                                                       srf.getRequestedService(), 0);

      Response authResponse = Utility.sendRequest(authURI, "PUT", tokenRequest);
      tokenResponse = authResponse.readEntity(TokenGenerationResponse.class);
    }

    List<String> tokens = tokenResponse.getToken();
    List<String> signatures = tokenResponse.getSignature();

    // We create an OrchestrationForm for every provider
    for (int i = 0; i < psList.size(); i++) {
      OrchestrationForm of = new OrchestrationForm(psList.get(i).getOffered(), psList.get(i).getProvider(), psList.get(i).getServiceURI(),
                                                   tokens.get(i), null, signatures.get(i));
      ofList.add(of);
    }

    log.info("OrchestrationForm created for " + psList.size() + " providers.");

    // The OrchestrationResponse contains a list of OrchestrationForms
    return new OrchestrationResponse(ofList);
  }

  /**
   * This method compiles the OrchestrationResponse object. Only the orchestrationFromStore method uses this version of the method.
   *
   * @return OrchestrationResponse
   */
  private static OrchestrationResponse compileOrchestrationResponseFromStore(List<OrchestrationStore> entryList, boolean generateToken) {
    log.info("Entered the (second) compileOrchestrationResponse method.");

    String token = null;
    List<OrchestrationForm> ofList = new ArrayList<>();
    // We create an OrchestrationForm for every Store entry
    for (OrchestrationStore entry : entryList) {
      if (generateToken) {
        // placeholder for token generation, call should be made to the
        // AuthorizationResource
      }

      //OrchestrationForm of = new OrchestrationForm(entry.getService(), entry.getProviderSystem(), null, token,entry.getOrchestrationRule());
      OrchestrationForm of = new OrchestrationForm();
      ofList.add(of);
    }
    log.info("OrchestrationForm created for " + entryList.size() + " providers.");

    // The OrchestrationResponse contains a list of OrchestrationForms
    return new OrchestrationResponse(ofList);
  }

  /**
   * Sends the QoS Verify message to the QoS service and asks for the QoS Verification Response .
   *
   * @ return QoSVerificationResponse
   */
  private static QoSVerificationResponse queryQoSVerification(QoSVerify qosVerify) {
    log.info("orchestrator: inside the getQoSVerificationResponse function");
    String URI = UriBuilder.fromPath(Utility.getQosUri()).path("verify").toString();
    Response response = Utility.sendRequest(URI, "PUT", Entity.json(qosVerify));
    return response.readEntity(QoSVerificationResponse.class);
  }

  /**
   * Sends QoS reservation to the QoS service.
   *
   * @return boolean indicating that the reservation completed successfully
   */
  private static QoSReservationResponse doQosReservation(QoSReserve qosReserve) {
    log.info("orchestrator: inside the doQoSReservation function");
    String URI = UriBuilder.fromPath(Utility.getQosUri()).path("reserve").toString();
    Response response = Utility.sendRequest(URI, "PUT", qosReserve);
    return response.readEntity(QoSReservationResponse.class);
  }

  private static List<ProvidedService> fancyQosMethodName(ServiceRequestForm srf, List<ProvidedService> psList){
    List<ArrowheadSystem> providerSystems = new ArrayList<>();
    for (ProvidedService service : psList) {
      providerSystems.add(service.getProvider());
    }

    QoSVerificationResponse qosVerificationResponse = queryQoSVerification(
        new QoSVerify(srf.getRequesterSystem(), srf.getRequestedService(), providerSystems, srf.getRequestedQoS(), srf.getCommands()));

    if (qosVerificationResponse != null) {  // Removing systems with inadequate QoS
      for (ArrowheadSystem provider : qosVerificationResponse.getResponse().keySet()) {
        if (!qosVerificationResponse.getResponse().get(provider)) {
          providerSystems.remove(provider);
        }
      }
    } else {
      //TODO throw exception + do we use the RejectMovivation field for something or drop it?
    }

    QoSReservationResponse qosReservationResponse;
    //TODO change the QoS Reservation to accept a list of providers!
    for (ArrowheadSystem provider : providerSystems) {
      qosReservationResponse = doQosReservation(
          new QoSReserve(provider, srf.getRequesterSystem(), srf.getRequestedService(), srf.getRequestedQoS(), srf.getCommands()));
      if (!qosReservationResponse.isSuccessfulReservation()) {
        providerSystems.remove(provider);
      }
    }
    for (ProvidedService service : psList) {
      if (!providerSystems.contains(service.getProvider())) {
        psList.remove(service);
      }
    }
    return null;
  }

}
