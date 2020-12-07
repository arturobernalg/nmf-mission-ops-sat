// (C) 2020 European Space Agency
// European Space Operations Centre
// Darmstadt, Germany

package esa.mo.nanomind.impl.parameters_provisioning;

import java.util.Date;
import org.ccsds.moims.mo.mal.structures.Attribute;

/**
 * Wrapper around an Attribute and the time at which it was updated.
 *
 * @author Tanguy Soto
 */
public class TimedAttributeValue {
  /**
   * The latest value
   */
  private Attribute value;

  /**
   * The latest update time of value
   */
  private Date lastUpdateTime;

  /**
   * Creates a new instance of TimedAttributeValue and sets the last update time to now.
   *
   * @param value The value
   */
  public TimedAttributeValue(Attribute value) {
    this.value = value;
    lastUpdateTime = new Date();
  }

  /**
   * @return The latest value
   */
  public Attribute getValue() {
    return value;
  }

  /**
   * Sets the value and updates the latest update time.
   * 
   * @param value the new value to set
   */
  public void setValue(Attribute value) {
    this.value = value;
    lastUpdateTime = new Date();
  }

  /**
   * Returns the latest update time of this value.
   * 
   * @return a Date containing the latest update time
   */
  public Date getLastUpdateTime() {
    return lastUpdateTime;
  }
}
