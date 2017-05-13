/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.loxone.core;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.openhab.binding.loxone.core.LxServerEvent.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loxone Miniserver representaton.
 * <p>
 * A Miniserver is identified by host address, user name and password used for logging into it.
 * They are provided to the constructor of this class. Upon creation of the object, you need to call
 * {@link #start()} method to initiate communication with the Miniserver, obtain its runtime configuration and
 * process live updates of controls' state changes.
 * <p>
 * Runtime configuration consists of following items:
 * <ul>
 * <li>server parameters: serial number, location, project name, server name
 * <li>'room' and 'category' proprietary display names
 * <li>a list of rooms
 * <li>a list of control categories
 * <li>a list of controls, each may be assigned to a room and a category
 * <li>a list of states for each of the controls
 * </ul>
 * <p>
 * Once server is populated with runtime configuration, its controls may be used to perform operations.
 * <p>
 * If server is not needed anymore, a {@link #stop()} method should be called to close open connections and stop
 * processing thread.
 *
 * @author Pawel Pieczul - initial commit
 *
 */
public class LxServer {

    // Configuration
    private final InetAddress host;
    private final int port;
    private final String user, password;
    private String miniserverName, projectName, location, serial, cloudAddress;
    private String roomTitle, categoryTitle;
    private int firstConDelay = 1, connectErrDelay = 10, userErrorDelay = 60, comErrorDelay = 30;

    // Data structures
    private Set<LxUuid> uuids = new HashSet<LxUuid>();
    private Map<LxUuid, LxControl> controls = new HashMap<LxUuid, LxControl>();;
    private Map<LxUuid, LxContainer> rooms = new HashMap<LxUuid, LxContainer>();
    private Map<LxUuid, LxCategory> categories = new HashMap<LxUuid, LxCategory>();
    private Map<LxUuid, LxControlState> states = new HashMap<LxUuid, LxControlState>();
    private List<LxServerListener> listeners = new ArrayList<LxServerListener>();

    // Services
    private boolean running = true;
    private LxWsClient socketClient;
    private Thread monitorThread = null;
    private BlockingQueue<LxServerEvent> queue = new LinkedBlockingQueue<LxServerEvent>();
    private Logger logger = LoggerFactory.getLogger(LxServer.class);
    private static int debugId = 1;

    /**
     * Reasons why Miniserver may be not reachable
     *
     * @author Pawel Pieczul - initial commit
     *
     */
    public enum OfflineReason {
        /**
         * No reason at all - should be reachable
         */
        NONE,
        /**
         * User name or password incorrect or user not authorized
         */
        UNAUTHORIZED,
        /**
         * Too many failed login attempts and server's temporary ban of the user
         */
        TOO_MANY_FAILED_LOGIN_ATTEMPTS,
        /**
         * Communication error with the Miniserv
         */
        COMMUNICATION_ERROR,
        /**
         * Timeout of user authentication procedure
         */
        AUTHENTICATION_TIMEOUT,
        /**
         * No activity from Miniserver's client
         */
        IDLE_TIMEOUT,
        /**
         * Internal error, sign of something wrong with the program
         */
        INTERNAL_ERROR,
        /**
         * Connection attempt failed (before authentication)
         */
        CONNECT_FAILED
    }

    /**
     * Creates a new instance of Loxone Miniserver with provided host address and credentials.
     *
     * @param host
     *            host address of the Miniserver
     * @param port
     *            web service port of the Miniserver
     * @param user
     *            user name used for logging in
     * @param password
     *            password used for logging in
     */
    public LxServer(InetAddress host, int port, String user, String password) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        socketClient = new LxWsClient(++debugId, queue, host, port, user, password);
    }

    /**
     * Initiate communication with the Miniserver.
     * Starts thread that handles communication.
     */
    public void start() {
        logger.debug("[{}] Server start", debugId);
        if (monitorThread == null) {
            monitorThread = new LxServerThread(this);
            monitorThread.start();
        }
    }

    /**
     * Stop server thread, close communication with Miniserver.
     */
    public void stop() {
        if (monitorThread != null) {
            logger.debug("[{}] Server stop", debugId);
            synchronized (monitorThread) {
                if (queue != null) {
                    LxServerEvent event = new LxServerEvent(EventType.CLIENT_CLOSING, LxServer.OfflineReason.NONE,
                            null);
                    try {
                        queue.put(event);
                        monitorThread.notify();
                    } catch (InterruptedException e) {
                        monitorThread.interrupt();
                    }
                } else {
                    monitorThread.interrupt();
                }
            }
        } else {
            logger.debug("[{}] Server stop - no thread", debugId);
        }
    }

    /**
     * Update server's configuration.
     * <p>
     * Only timeout parameters can be updated in runtime without breaking connection to the Miniserver.
     * If other parameters must be changed, server should be stopped and a new instance created.
     *
     * @param firstConDelay
     *            Time in seconds between binding initialization and first connection attempt
     * @param keepAlivePeriod
     *            Time in seconds between sending two consecutive keep-alive messages
     * @param connectErrDelay
     *            Time in seconds between failed websocket connect attempts
     * @param userErrorDelay
     *            Time in seconds between user login error as a result of wrong name/password or no authority and next
     *            connection attempt
     * @param comErrorDelay
     *            Time in seconds between connection close (as a result of some communication error) and next connection
     *            attempt
     */
    public void update(int firstConDelay, int keepAlivePeriod, int connectErrDelay, int userErrorDelay,
            int comErrorDelay) {
        logger.debug("[{}] Server update configuration", debugId);
        if (firstConDelay >= 0) {
            this.firstConDelay = firstConDelay;
        }
        if (connectErrDelay >= 0) {
            this.connectErrDelay = connectErrDelay;
        }
        if (userErrorDelay >= 0) {
            this.userErrorDelay = userErrorDelay;
        }
        if (comErrorDelay >= 0) {
            this.comErrorDelay = comErrorDelay;
        }
        if (socketClient != null) {
            socketClient.update(keepAlivePeriod);
        }
    }

    /**
     * Adds a listener to server's events
     *
     * @param listener
     *            an object implementing server's listener interface
     */
    public void addListener(LxServerListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a listener of server's events
     *
     * @param listener
     *            listener object to remove
     */
    public void removeListener(LxServerListener listener) {
        listeners.remove(listener);
    }

    /**
     * Checks if current Miniserver configuration differs from provided parameters.
     *
     * @param host
     *            A new host address to check against
     * @param user
     *            A new user name to check against
     * @param port
     *            A new web service port to check against
     * @param password
     *            A new password to check against
     * @return
     *         true if current Miniserver configuration is different
     */
    public boolean isChanged(InetAddress host, int port, String user, String password) {
        return (!(this.port == port && this.host.toString().equals(host.toString()) && this.user.equals(user)
                && this.password.equals(password)));
    }

    /**
     * Searches for a control with given UUID
     *
     * @param id
     *            UUID of the control to locate
     * @return
     *         Found control or null if not found
     */
    public LxControl findControl(LxUuid id) {
        if (controls == null || id == null) {
            return null;
        }
        if (controls.containsKey(id)) {
            return controls.get(id);
        }
        return null;
    }

    /**
     * Searches for a control with given name (descriptive)
     *
     * @param name
     *            A name of the control to locate
     * @return
     *         Found control or null if not found
     */
    public LxControl findControl(String name) {
        for (LxControl l : controls.values()) {
            if (l.getName().equals(name)) {
                return l;
            }
        }
        return null;
    }

    /**
     * Gets a set of all controls for this Miniserver
     *
     * @return Map of controls with UUID as a key
     */
    public Map<LxUuid, LxControl> getControls() {
        return controls;
    }

    /**
     * Gets Miniserver name
     *
     * @return Miniserver name
     */
    public String getMiniserverName() {
        return miniserverName;
    }

    /**
     * Gets project name as configured on the Miniserver
     *
     * @return project name
     */
    public String getProjectName() {
        return projectName;
    }

    /**
     * Gets Miniserver cloud address
     *
     * @return cloud URL
     */
    public String getCloudAddress() {
        return cloudAddress;
    }

    /**
     * Gets device location as configured on the Miniserver
     *
     * @return Description of the device location
     */
    public String getLocation() {
        return location;
    }

    /**
     * Gets device serial number as configured on the Miniserver
     *
     * @return Device serial number
     */
    public String getSerial() {
        return serial;
    }

    /**
     * Thread that performs and supervises communication with the Miniserver.
     * It will try to maintain connection as long as possible, handling errors and interruptions. There are two reasons
     * when this thread will terminate and stop connecting to the Miniserver:
     * when it receives close command from supervisor ({@link LxServer} or when Miniserver locks out user due to too
     * many unsuccessful login attempts.
     *
     * @author Pawel Pieczul - initial commit
     *
     */
    private class LxServerThread extends Thread {
        LxServer server;

        LxServerThread(LxServer server) {
            this.server = server;
        }

        @Override
        public void run() {
            logger.debug("[{}] Thread starting", debugId);

            // initial delay to initiate connection
            int waitTime = firstConDelay * 1000;

            while (running) {
                // wait until next connect attempt, this time depends on what happened before
                synchronized (monitorThread) {
                    try {
                        monitorThread.wait(waitTime);
                    } catch (InterruptedException e) {
                        logger.debug("[{}] Server thread sleep interrupted, terminating", debugId);
                        running = false;
                        break;
                    }
                }

                // attempt to connect to the Miniserver
                logger.debug("[{}] Server connecting to websocket", debugId);
                boolean connected = socketClient.connect();
                if (!connected) {
                    logger.debug("[{}] Websocket connect failed, retrying after pause", debugId);
                    waitTime = connectErrDelay * 1000;
                    continue;
                }

                while (connected) {
                    try {
                        LxServerEvent wsMsg = queue.take();
                        EventType event = wsMsg.getEvent();
                        logger.debug("[{}] Server received event: {}", debugId, event.toString());
                        switch (event) {
                            case RECEIVED_CONFIG:
                                LxJsonApp3 config = (LxJsonApp3) wsMsg.getObject();
                                if (config != null) {
                                    updateConfig(config);
                                    for (LxServerListener listener : listeners) {
                                        listener.onNewConfig(server);
                                    }
                                } else {
                                    logger.debug("[{}] Server failed processing received configuration", debugId);
                                }
                                break;
                            case STATE_VALUE_UPDATE:
                                LxWsStateUpdateEvent update = (LxWsStateUpdateEvent) wsMsg.getObject();
                                LxControlState state = findState(update.getUuid());
                                if (state != null) {
                                    state.setValue(update.getValue());
                                    LxControl control = state.getControl();
                                    if (control != null) {
                                        logger.debug("[{}] State update {} ({}:{}) to value {}", debugId,
                                                update.getUuid().toString(), control.getName(), state.getName(),
                                                update.getValue());
                                        for (LxServerListener listener : listeners) {
                                            listener.onControlStateUpdate(control);
                                        }
                                    } else {
                                        logger.debug("[{}] State update {} ({}) of unknown control", debugId,
                                                update.getUuid().toString(), state.getName());
                                    }
                                }
                                break;
                            case SERVER_ONLINE:
                                for (LxServerListener listener : listeners) {
                                    listener.onServerGoesOnline();
                                }
                                break;
                            case SERVER_OFFLINE:
                                logger.debug("[{}] Websocket goes OFFLINE, reason {}.", debugId,
                                        wsMsg.getOfflineReason().toString());

                                OfflineReason reason = wsMsg.getOfflineReason();
                                if (reason == OfflineReason.TOO_MANY_FAILED_LOGIN_ATTEMPTS) {
                                    // assume credentials are wrong, do not re-attempt connections
                                    // close thread and expect a new LxServer object will have to be re-created
                                    // with corrected configuration
                                    running = false;
                                } else {
                                    if (reason == OfflineReason.UNAUTHORIZED) {
                                        waitTime = userErrorDelay * 1000;
                                    } else {
                                        waitTime = comErrorDelay * 1000;
                                    }
                                    socketClient.disconnect();
                                }
                                connected = false;
                                for (LxServerListener listener : listeners) {
                                    listener.onServerGoesOffline(reason);
                                }
                                break;
                            case CLIENT_CLOSING:
                                connected = false;
                                running = false;
                                break;
                            default:
                                logger.debug("[{}] Received unknown request {}", debugId, wsMsg.getEvent().name());
                                break;
                        }
                    } catch (InterruptedException e) {
                        logger.debug("[{}] Waiting for sync event interrupted, reason = {}", debugId, e.getMessage());
                        connected = false;
                        running = false;
                    }
                }
            }
            logger.debug("[{}] Thread ending", debugId);
            socketClient.disconnect();
            monitorThread = null;
            queue = null;
        }
    }

    /**
     * Updates runtime configuration from parsed JSON configuration file of Loxone Miniserver (LoxApp3.json)
     *
     * @param config
     *            parsed JSON LoxApp3.json file
     */
    private void updateConfig(LxJsonApp3 config) {

        for (LxUuid id : uuids) {
            id.setUpdate(false);
        }

        miniserverName = buildName(config.msInfo.msName);
        projectName = buildName(config.msInfo.projectName);
        location = buildName(config.msInfo.location);
        serial = buildName(config.msInfo.serialNr);
        roomTitle = buildName(config.msInfo.roomTitle);
        categoryTitle = buildName(config.msInfo.catTitle);
        cloudAddress = buildName(config.msInfo.remoteUrl);

        // create internal structures based on configuration file
        for (LxJsonApp3.LxJsonRoom room : config.rooms.values()) {
            addOrUpdateRoom(new LxUuid(room.uuid), room.name);
        }
        for (LxJsonApp3.LxJsonCat cat : config.cats.values()) {
            addOrUpdateCategory(new LxUuid(cat.uuid), cat.name, cat.type);
        }
        for (LxJsonApp3.LxJsonControl ctrl : config.controls.values()) {

            Map<String, LxControlState> states = new HashMap<String, LxControlState>();
            for (Map.Entry<String, String> state : ctrl.states.entrySet()) {

                LxUuid stateId = new LxUuid(state.getValue());
                String stateName = state.getKey();
                LxControlState controlState = findState(stateId);
                if (controlState == null) {
                    stateId = addUuid(stateId);
                    controlState = new LxControlState(stateId, stateName, null);
                } else {
                    controlState.setName(stateName);
                }
                states.put(stateName, controlState);
            }

            LxUuid catUuid = null;
            if (ctrl.cat != null) {
                catUuid = new LxUuid(ctrl.cat);
            }
            LxUuid roomUuid = null;
            if (ctrl.room != null) {
                roomUuid = new LxUuid(ctrl.room);
            }

            // create a new control or update existing one
            LxControl control = addOrUpdateControl(new LxUuid(ctrl.uuidAction), ctrl.name, ctrl.type, roomUuid, catUuid,
                    states);

            if (control != null) {
                // if control was created, set its states objects
                for (LxControlState state : states.values()) {
                    this.states.put(state.getUuid(), state);
                }
            }
        }
        // remove items that do not exist anymore in Miniserver
        for (LxUuid id : rooms.keySet()) {
            if (!id.getUpdate()) {
                rooms.remove(id);
                uuids.remove(id);
            }
        }
        for (LxUuid id : categories.keySet()) {
            if (!id.getUpdate()) {
                categories.remove(id);
                uuids.remove(id);
            }
        }
        for (LxUuid id : controls.keySet()) {
            if (!id.getUpdate()) {
                controls.remove(id);
                uuids.remove(id);
            }
        }
        for (LxUuid id : states.keySet()) {
            if (!id.getUpdate()) {
                states.remove(id);
            }
        }
    }

    /**
     * Searches for an UUID of an object (control, category, room, ...) that this server contains
     *
     * @param id
     *            UUID to search for
     * @return
     *         found UUID object or null if not found
     */
    private LxUuid findUuid(LxUuid id) {
        if (uuids == null || id == null) {
            return null;
        }
        for (LxUuid i : uuids) {
            if (id.equals(i)) {
                return i;
            }
        }
        return null;
    }

    /**
     * Adds a new UUID object to server, if an object with same UUID value does not exist yet. Otherwise return
     * already existing object.
     *
     * @param id
     *            UUID to search for
     * @return
     *         object belonging to server (either same as provided as an argument or already existing one)
     */
    private LxUuid addUuid(LxUuid id) {
        LxUuid i = findUuid(id);
        if (i != null) {
            return i;
        }
        uuids.add(id);
        return id;
    }

    /**
     * Search for a room with given UUID
     *
     * @param id
     *            UUID of a room to search for
     * @return
     *         found room on null if not found
     */
    private LxContainer findRoom(LxUuid id) {
        if (rooms == null || id == null) {
            return null;
        }
        if (rooms.containsKey(id)) {
            return rooms.get(id);
        }
        return null;
    }

    /**
     * Add a room to the server, if a room with same UUID already does not exist, otherwise update it with new name.
     *
     * @param id
     *            UUID of the room to add
     * @param name
     *            name of the room to add
     * @return
     *         room object (either newly created or already existing) or null if wrong parameters
     */
    private LxContainer addOrUpdateRoom(LxUuid id, String name) {
        if (rooms == null) {
            return null;
        }
        LxContainer r = findRoom(id);
        if (r != null) {
            r.setName(name);
            return r;
        }
        id = addUuid(id);
        LxContainer nr = new LxContainer(id, name);
        rooms.put(id, nr);
        return nr;
    }

    /**
     * Search for a state with given UUID
     *
     * @param id
     *            UUID of state to locate
     * @return
     *         state object
     */
    private LxControlState findState(LxUuid id) {
        if (states == null || id == null) {
            return null;
        }
        if (states.containsKey(id)) {
            return states.get(id);
        }
        return null;
    }

    /**
     * Search for a category on the server
     *
     * @param id
     *            UUID of the category to find
     * @return
     *         category object found or null if not found
     */
    private LxCategory findCategory(LxUuid id) {
        if (categories == null || id == null) {
            return null;
        }
        if (categories.containsKey(id)) {
            return categories.get(id);
        }
        return null;
    }

    /**
     * Add a new category or update and return existing one with same UUID
     *
     * @param id
     *            UUID of the category to add or update
     * @param name
     *            name of the category
     * @param type
     *            type of the category
     * @return
     *         newly added category or already existing and updated, null if wrong parameters/configuration
     */
    private LxCategory addOrUpdateCategory(LxUuid id, String name, String type) {
        if (categories == null) {
            return null;
        }
        LxCategory c = findCategory(id);
        if (c != null) {
            c.setName(name);
            c.setType(type);
            return c;
        }
        id = addUuid(id);
        LxCategory nc = new LxCategory(id, name, type);
        categories.put(id, nc);
        return nc;
    }

    /**
     * Add a new control or update and return existing one with same UUID
     *
     * @param id
     *            UUID of control to add
     * @param name
     *            name of control to add
     * @param type
     *            type of control to add
     * @param roomId
     *            UUID of room this control belongs to
     * @param categoryId
     *            UUID of category this control belongs to
     * @return
     *         newly created control or existing one, null if incorrect parameters/configuration
     */
    private LxControl addOrUpdateControl(LxUuid id, String name, String type, LxUuid roomId, LxUuid categoryId,
            Map<String, LxControlState> states) {

        if (controls == null) {
            return null;
        }
        LxContainer room = findRoom(roomId);
        LxCategory category = findCategory(categoryId);

        LxControl c = findControl(id);
        if (c != null) {
            c.update(name, room, category, states);
            return c;
        }

        id = addUuid(id);

        type = type.toLowerCase();

        LxControl ctrl = null;
        if (type.equals(LxControlSwitch.TYPE_NAME)) {
            ctrl = new LxControlSwitch(socketClient, id, name, room, category, states);
        } else if (type.equals(LxControlJalousie.TYPE_NAME)) {
            ctrl = new LxControlJalousie(socketClient, id, name, room, category, states);
        }

        if (ctrl != null) {
            controls.put(id, ctrl);
        }
        return ctrl;
    }

    /**
     * Check and converts null string to empty string.
     *
     * @param name
     *            string to check
     * @return
     *         string guaranteed to not be null
     */
    private String buildName(String name) {
        if (name == null) {
            return "";
        }
        return name;
    }

    /**
     * Logs all UUIDs recognized on the Miniserver (rooms, categories, controls).
     */
    public void traceUuids() {
        logger.trace("*** (" + uuids.size() + ") UUIDS ***");
        for (LxUuid i : uuids) {
            logger.trace("Uuid: " + i.toString());
        }
    }

    /**
     * Logs all rooms recognized on the Miniserever
     */
    public void traceRooms() {
        logger.trace("*** (" + rooms.size() + ") ROOMS (" + roomTitle + ") ***");
        for (LxContainer r : rooms.values()) {
            logger.trace(r.toString());
        }
    }

    /**
     * Logs all categories recognized on the Miniserver
     */
    public void traceCategories() {
        logger.trace("*** (" + categories.size() + ") CATEGORIES (" + categoryTitle + ") ***");
        for (LxContainer c : categories.values()) {
            logger.trace(c.toString());
        }
    }

    /**
     * Logs all controls recognized on the Miniserver
     */
    public void traceControls() {
        logger.trace("*** (" + controls.size() + ") CONTROLS ***");
        for (LxControl c : controls.values()) {
            logger.trace(c.toString());
        }
    }

    /**
     * Logs general Miniserver configuration
     */
    public void traceInfo() {
        logger.trace("host       :" + host.toString());
        logger.trace("port       :" + port);
        logger.trace("Miniserver :" + miniserverName);
        logger.trace("Project    :" + projectName);
        logger.trace("Location   :" + location);
        logger.trace("Serial     :" + serial);
    }
}