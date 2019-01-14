/*
 * This work is part of the Productive 4.0 innovation project, which receives grants from the
 * European Commissions H2020 research and innovation programme, ECSEL Joint Undertaking
 * (project no. 737459), the free state of Saxony, the German Federal Ministry of Education and
 * national funding authorities from involved countries.
 */

package eu.arrowhead.common.web;

import eu.arrowhead.common.DatabaseManager;
import eu.arrowhead.common.database.entity.ArrowheadSystem;
import eu.arrowhead.common.exception.DataNotFoundException;
import eu.arrowhead.common.messages.ArrowheadSystemDTO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.apache.log4j.Logger;

@Path("mgmt/systems")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ArrowheadSystemApi {

  private final HashMap<String, Object> restrictionMap = new HashMap<>();
  private static final Logger log = Logger.getLogger(ArrowheadSystemApi.class.getName());
  private static final DatabaseManager dm = DatabaseManager.getInstance();

  @GET
  public List<ArrowheadSystem> getSystems(@QueryParam("systemName") String systemName) {
    if (systemName != null) {
      restrictionMap.put("systemName", systemName);
    }

    List<ArrowheadSystem> systemList = dm.getAll(ArrowheadSystem.class, restrictionMap);
    if (systemList.isEmpty()) {
      log.info("getSystems throws DataNotFoundException");
      throw new DataNotFoundException("ArrowheadSystems not found in the database.");
    }

    return systemList;
  }

  @GET
  @Path("{systemId}")
  public ArrowheadSystem getSystem(@PathParam("systemId") long systemId) {
    return dm.get(ArrowheadSystem.class, systemId)
             .orElseThrow(() -> new DataNotFoundException("ArrowheadSystem entry not found with id: " + systemId));
  }

  @POST
  public Response addSystems(@Valid List<ArrowheadSystemDTO> systemList) {
    List<ArrowheadSystem> convertedSystems = new ArrayList<>();
    for (ArrowheadSystemDTO system : systemList) {
      convertedSystems.add(ArrowheadSystem.convertToEntity(system));
    }

    List<ArrowheadSystem> savedSystems = new ArrayList<>();
    for (ArrowheadSystem system : convertedSystems) {
      restrictionMap.clear();
      restrictionMap.put("systemName", system.getSystemName());
      ArrowheadSystem retrievedSystem = dm.get(ArrowheadSystem.class, restrictionMap);
      if (retrievedSystem == null) {
        dm.save(system);
        savedSystems.add(system);
      }
    }

    if (savedSystems.isEmpty()) {
      return Response.status(Status.NO_CONTENT).build();
    } else {
      List<ArrowheadSystem> savedD
      return Response.status(Status.CREATED).entity(savedSystems).build();
    }
  }

  @PUT
  public Response updateSystem(@Valid ArrowheadSystemDTO system) {
    ArrowheadSystem updatedSystem = ArrowheadSystem.convertToEntity(system);
    restrictionMap.put("systemName", updatedSystem.getSystemName());

    ArrowheadSystem retrievedSystem = dm.get(ArrowheadSystem.class, restrictionMap);
    if (retrievedSystem != null) {
      retrievedSystem.setAddress(updatedSystem.getAddress());
      retrievedSystem.setPort(updatedSystem.getPort());
      retrievedSystem.setAuthenticationInfo(updatedSystem.getAuthenticationInfo());
      retrievedSystem = dm.merge(retrievedSystem);
      return Response.accepted().entity(ArrowheadSystem.convertToDTO(retrievedSystem, true)).build();
    } else {
      return Response.noContent().build();
    }
  }

  @PUT
  @Path("{systemId}")
  public Response updateSystem(@PathParam("systemId") long systemId, @Valid ArrowheadSystemDTO system) {
    ArrowheadSystem retrievedSystem = dm.get(ArrowheadSystem.class, systemId).orElseThrow(
        () -> new DataNotFoundException("ArrowheadSystem entry not found with id: " + systemId));

    ArrowheadSystem updatedSystem = ArrowheadSystem.convertToEntity(system);
    retrievedSystem.partialUpdate(updatedSystem);
    retrievedSystem = dm.merge(retrievedSystem);
    return Response.accepted().entity(ArrowheadSystem.convertToDTO(retrievedSystem, true)).build();
  }

  @DELETE
  @Path("{systemId}")
  public Response deleteSystem(@PathParam("systemId") long systemId) {
    return dm.get(ArrowheadSystem.class, systemId).map(system -> {
      dm.delete(system);
      log.info("deleteSystem successfully returns.");
      return Response.ok().build();
    }).<DataNotFoundException>orElseThrow(() -> {
      log.info("deleteSystem had no effect.");
      throw new DataNotFoundException("ArrowheadSystem entry not found with id: " + systemId);
    });
  }

}
