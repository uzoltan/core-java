/*
 * This work is part of the Productive 4.0 innovation project, which receives grants from the
 * European Commissions H2020 research and innovation programme, ECSEL Joint Undertaking
 * (project no. 737459), the free state of Saxony, the German Federal Ministry of Education and
 * national funding authorities from involved countries.
 */

package eu.arrowhead.common.messages;

import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Objects;
import javax.validation.Valid;

public class NeighborCloudDTO implements Serializable {

  @Valid
  private ArrowheadCloudDTO cloud;

  public NeighborCloudDTO() {
  }

  public NeighborCloudDTO(ArrowheadCloudDTO cloud) {
    this.cloud = cloud;
  }

  public ArrowheadCloudDTO getCloud() {
    return cloud;
  }

  public void setCloud(ArrowheadCloudDTO cloud) {
    this.cloud = cloud;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof NeighborCloudDTO)) {
      return false;
    }
    NeighborCloudDTO that = (NeighborCloudDTO) o;
    return Objects.equals(cloud, that.cloud);
  }

  @Override
  public int hashCode() {
    return Objects.hash(cloud);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("cloud", cloud).toString();
  }
}
