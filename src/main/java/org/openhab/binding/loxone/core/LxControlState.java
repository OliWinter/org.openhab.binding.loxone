/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.loxone.core;

/**
 * A state of a Loxone control ({@link LxControl})
 * <p>
 * Each control object may have a number of states defined, that describe the overall condition of the control.
 * List of states is read from LoxApp3.json configuration file.
 * <p>
 * Each state is identified by its own UUID and a name of the state. Names are proprietary to a particular type of the
 * control and as such are defined in {@link LxControl} child classes implementation (e.g. {@link LxControlSwitch}
 * Objects of this class are used to bind state updates received from the Miniserver to a control object.
 *
 * @author Pawel Pieczul - initial commit
 *
 */
public class LxControlState {
    private LxUuid uuid;
    private String name;
    private double value;
    private LxControl control;

    /**
     * Create a control state object.
     *
     * @param uuid
     *            UUID of the state
     * @param name
     *            name of the state
     * @param control
     *            control to which this state belongs
     */
    public LxControlState(LxUuid uuid, String name, LxControl control) {
        this.uuid = uuid;
        this.name = name;
        this.control = control;
        value = 0;
        uuid.setUpdate(true);
    }

    /**
     * Sets current value of the control's state
     *
     * @param value
     *            current state's value to set
     */
    public void setValue(double value) {
        this.value = value;
        uuid.setUpdate(true);
    }

    /**
     * Gets current value of the control's state
     *
     * @return
     *         current state's value
     */
    public double getValue() {
        return value;
    }

    /**
     * Gets control to which state belongs
     *
     * @return
     *         state's control object
     */
    public LxControl getControl() {
        return control;
    }

    /**
     * Sets control to which state belongs.
     *
     * @param control
     *            state's control object
     */
    public void setControl(LxControl control) {
        this.control = control;
        uuid.setUpdate(true);
    }

    /**
     * Gets state's name.
     * <p>
     * State's name is proprietary per control type.
     *
     * @return
     *         state's name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets state's name
     *
     * @param name
     *            state's name
     */
    public void setName(String name) {
        this.name = name;
        uuid.setUpdate(true);
    }

    /**
     * Gets UUID of the state
     *
     * @return
     *         state's UUID
     */
    public LxUuid getUuid() {
        return uuid;
    }
}