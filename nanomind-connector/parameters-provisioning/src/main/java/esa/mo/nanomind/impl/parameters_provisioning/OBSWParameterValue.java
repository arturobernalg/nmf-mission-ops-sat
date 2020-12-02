// (C) 2020 European Space Agency
// European Space Operations Centre
// Darmstadt, Germany

package esa.mo.nanomind.impl.parameters_provisioning;

import java.util.Date;
import org.ccsds.moims.mo.mal.structures.Attribute;
import esa.mo.nmf.nanosatmosupervisor.parameter.OBSWParameter;

/**
 * Wrapper around an OBSW parameter and it's latest value and the time at which it was updated.
 *
 * @author Tanguy Soto
 */
public class OBSWParameterValue {
  /**
   * The OBSW parameter for which we hold a value.
   */
  private OBSWParameter parameter;

  /**
   * The latest value of the associated OBSW parameter.
   */
  private Attribute value;

  /**
   * The latest update time of this parameter value
   */
  private Date lastUpdateTime;


  /**
   * Creates a new instance of OBSWParameterValue.
   * 
   * @param parameter The OBSW parameter we wrap
   * @param value The value to associate to the parameter
   */
  public OBSWParameterValue(OBSWParameter parameter, Attribute value) {
    this.parameter = parameter;
    this.value = value;
    lastUpdateTime = new Date();
  }

  /**
   * Returns the OBSW parameter for which this class holds a value.
   * 
   * @return the OBSWParameter instance
   */
  public OBSWParameter getParameter() {
    return parameter;
  }

  /**
   * Returns the latest value of the associated OBSW parameter.
   * 
   * @return an Attribute or null if the value was never set.
   */
  public Attribute getValue() {
    return value;
  }

  /**
   * Sets the value for the associated OBSW parameter and updates the lastest update time.
   * 
   * @param value an Attribute containing the value to set
   */
  public void setValue(Attribute value) {
    this.value = value;
    lastUpdateTime = new Date();
  }

  /**
   * Returns the lastest update time of this parameter value.
   * 
   * @return a Date containing the lastest update time of null if the value was never set.
   */
  public Date getLastUpdateTime() {
    return lastUpdateTime;
  }
}
