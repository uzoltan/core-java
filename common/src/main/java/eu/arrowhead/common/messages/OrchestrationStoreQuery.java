package eu.arrowhead.common.messages;


import com.fasterxml.jackson.annotation.JsonIgnore;
import eu.arrowhead.common.database.ArrowheadService;
import eu.arrowhead.common.database.ArrowheadSystem;

public class OrchestrationStoreQuery {

  private ArrowheadService requestedService;
  private ArrowheadSystem requesterSystem;

  public OrchestrationStoreQuery() {
  }

  public OrchestrationStoreQuery(ArrowheadService requestedService, ArrowheadSystem requesterSystem) {
    this.requestedService = requestedService;
    this.requesterSystem = requesterSystem;
  }

  public ArrowheadService getRequestedService() {
    return requestedService;
  }

  public void setRequestedService(ArrowheadService requestedService) {
    this.requestedService = requestedService;
  }

  public ArrowheadSystem getRequesterSystem() {
    return requesterSystem;
  }

  public void setRequesterSystem(ArrowheadSystem requesterSystem) {
    this.requesterSystem = requesterSystem;
  }

  @JsonIgnore
  public boolean isValid() {
    if (requesterSystem == null && requestedService == null) {
      return false;
    }
    if (requestedService != null && !requestedService.isValid()) {
      return false;
    }
    return requesterSystem == null || requesterSystem.isValid();
  }

}
