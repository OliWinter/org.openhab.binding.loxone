/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.loxone.handler;

import static org.openhab.binding.loxone.LoxoneBindingConstants.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StopMoveType;
import org.eclipse.smarthome.core.library.types.UpDownType;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.thing.type.ChannelType;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.loxone.config.LoxoneMiniserverConfig;
import org.openhab.binding.loxone.core.LxCategory;
import org.openhab.binding.loxone.core.LxControl;
import org.openhab.binding.loxone.core.LxControlJalousie;
import org.openhab.binding.loxone.core.LxControlState;
import org.openhab.binding.loxone.core.LxControlSwitch;
import org.openhab.binding.loxone.core.LxServer;
import org.openhab.binding.loxone.core.LxServerListener;
import org.openhab.binding.loxone.core.LxUuid;
import org.openhab.binding.loxone.internal.LoxoneHandlerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Representation of a Loxone Miniserver. It is an OpenHAB {@link Thing}, which is used to communicate with
 * objects (controls) configured in the Miniserver over {@link Channels}.
 *
 * @author Pawel Pieczul - Initial contribution
 */
public class LoxoneMiniserverHandler extends BaseThingHandler implements LxServerListener {

    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Collections.singleton(THING_TYPE_MINISERVER);

    private LxServer server = null;
    private LoxoneHandlerFactory factory;
    private Logger logger = LoggerFactory.getLogger(LoxoneMiniserverHandler.class);

    /**
     * Create {@link LoxoneMiniserverHandler} object
     *
     * @param thing
     *            Thing object that creates the handler
     * @param factory
     *            factory that creates the handler
     */
    public LoxoneMiniserverHandler(Thing thing, LoxoneHandlerFactory factory) {
        super(thing);
        this.factory = factory;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

        if (server == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "No server attached to this thing");
            return;
        }

        LxControl control = getControlFromChannelUID(channelUID);
        if (control == null) {
            logger.error("Received command {} from unknown control.", command.toString());
            return;
        }

        logger.debug("Control '{}' received command: {}", control.getName(), command.toString());

        // do not check for compatibility between command and control type here, each control has to do it itself
        try {
            if (command instanceof RefreshType) {
                updateChannelState(channelUID, control);
                return;
            }

            if (control instanceof LxControlSwitch) {
                if (command instanceof OnOffType) {
                    if ((OnOffType) command == OnOffType.ON) {
                        ((LxControlSwitch) control).On();
                    } else {
                        ((LxControlSwitch) control).Off();
                    }
                }
                return;
            }

            if (control instanceof LxControlJalousie) {

                LxControlJalousie jalousie = (LxControlJalousie) control;
                if (command instanceof PercentType) {
                    // currently no support for moving blinds to given percent
                    updateChannelState(channelUID, control);
                } else if (command instanceof UpDownType) {
                    if ((UpDownType) command == UpDownType.UP) {
                        jalousie.FullUp();
                    } else {
                        jalousie.FullDown();
                    }
                } else if (command instanceof StopMoveType) {
                    if ((StopMoveType) command == StopMoveType.STOP) {
                        jalousie.Stop();
                    }
                }
                return;
            }
            logger.debug("Incompatible operation on control {}", control.getUuid().toString());

        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        logger.info("Channel linked: " + channelUID.getAsString());
        LxControl control = getControlFromChannelUID(channelUID);
        if (control != null) {
            updateChannelState(channelUID, control);
        }
    }

    @Override
    public void initialize() {
        LoxoneMiniserverConfig cfg = getConfig().as(LoxoneMiniserverConfig.class);
        try {
            InetAddress ip = InetAddress.getByName(cfg.host);

            // check if server does not need to be created from scratch
            if (server != null && !server.isChanged(ip, cfg.port, cfg.user, cfg.password)) {
                server.update(cfg.firstConDelay, cfg.keepAlivePeriod, cfg.connectErrDelay, cfg.userErrorDelay,
                        cfg.comErrorDelay);
            } else {
                if (server != null) {
                    server.stop();
                }
                server = new LxServer(ip, cfg.port, cfg.user, cfg.password);
                server.addListener(this);
                server.update(cfg.firstConDelay, cfg.keepAlivePeriod, cfg.connectErrDelay, cfg.userErrorDelay,
                        cfg.comErrorDelay);
                server.start();
            }
        } catch (UnknownHostException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Unknown host");
        }
    }

    @Override
    public void onNewConfig(LxServer server) {
        Thing thing = getThing();
        thing.setProperty(MINISERVER_PROPERTY_MINISERVER_NAME, server.getMiniserverName());
        thing.setProperty(MINISERVER_PROPERTY_SERIAL, server.getSerial());
        thing.setProperty(MINISERVER_PROPERTY_PROJECT_NAME, server.getProjectName());
        thing.setProperty(MINISERVER_PROPERTY_CLOUD_ADDRESS, server.getCloudAddress());
        // set location only the first time after discovery
        if (thing.getLocation() == null) {
            thing.setLocation(server.getLocation());
        }

        ArrayList<Channel> channels = new ArrayList<Channel>();
        ThingBuilder builder = editThing();

        for (Channel channel : getThing().getChannels()) {
            if (server.findControl(new LxUuid(channel.getUID().getIdWithoutGroup())) == null) {
                builder.withoutChannel(channel.getUID());
            }
        }

        for (LxControl control : server.getControls().values()) {

            Channel channel = null;
            ChannelTypeUID chTypeId = null;
            ChannelType channelType = null;
            ChannelUID channelId = new ChannelUID(getThing().getUID(), control.getUuid().toString());

            if (control instanceof LxControlSwitch) {

                chTypeId = new ChannelTypeUID(getThing().getUID() + ":switch:" + control.getUuid().toString());

                Set<String> tags = null;
                LxCategory cat = control.getCategory();
                if (cat != null && cat.getType() == LxCategory.CategoryType.LIGHTS) {
                    tags = Collections.singleton("Lighting");
                }

                channelType = new ChannelType(chTypeId, false, "Switch", control.getName(),
                        "Loxone switch for " + control.getName(), control.getCategory().getName(), tags, null, null);
                channel = ChannelBuilder.create(channelId, "Switch").withType(chTypeId).withLabel(control.getName())
                        .withDescription("Switch for " + control.getName()).withDefaultTags(tags).build();

            } else if (control instanceof LxControlJalousie) {

                chTypeId = new ChannelTypeUID(getThing().getUID() + ":rollershutter:" + control.getUuid().toString());
                channelType = new ChannelType(chTypeId, false, "Rollershutter", control.getName(),
                        "Loxone jalousie control for " + control.getName(), control.getCategory().getName(), null, null,
                        null);
                channel = ChannelBuilder.create(channelId, "Rollershutter").withType(chTypeId)
                        .withLabel(control.getName()).withDescription("Rollershutter for " + control.getName()).build();
            }

            if (channel != null && channelType != null) {
                factory.addChannelType(channelType);
                builder.withoutChannel(channelId);
                channels.add(channel);
            }
        }

        builder.withChannels(channels);

        updateThing(builder.build());

    }

    @Override
    public void onControlStateUpdate(LxControl control) {
        ChannelUID channelId = new ChannelUID(getThing().getUID(), control.getUuid().toString());
        updateChannelState(channelId, control);
    }

    @Override
    public void onServerGoesOnline() {
        updateStatus(ThingStatus.ONLINE);
    }

    @Override
    public void onServerGoesOffline(LxServer.OfflineReason reason) {
        switch (reason) {
            case AUTHENTICATION_TIMEOUT:
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "User authentication timeout");
                break;
            case COMMUNICATION_ERROR:
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Error communicating with Miniserver");
                break;
            case INTERNAL_ERROR:
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Internal error");
                break;
            case TOO_MANY_FAILED_LOGIN_ATTEMPTS:
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "Too many failed login attempts - stopped trying");
                break;
            case UNAUTHORIZED:
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "User not authorized");
                break;
            case IDLE_TIMEOUT:
                logger.warn("Idle timeout from Loxone Miniserver - adjust keepalive settings");
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Timeout due to no activity");
                break;
            default:
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Unknown reason");
                break;
        }
    }

    @Override
    public void dispose() {
        if (server != null) {
            server.stop();
            server = null;
        }
        factory.removeChannelTypesForThing(getThing().getUID());
    }

    private void updateChannelState(ChannelUID channelUID, LxControl control) {
        if (control instanceof LxControlSwitch) {
            LxControlState state = control.getState(LxControlSwitch.STATE_ACTIVE);
            double value = state.getValue();
            if (value == 1.0) {
                updateState(channelUID, OnOffType.ON);
            } else if (value == 0) {
                updateState(channelUID, OnOffType.OFF);
            }
        } else if (control instanceof LxControlJalousie) {
            LxControlState state = control.getState(LxControlJalousie.STATE_POSITION);
            if (state != null) {
                updateState(channelUID, new PercentType((int) (state.getValue() * 100)));
            }
            // state UP or DOWN from Loxone indicates blinds are moving up or down
            // state UP in OpenHAB means blinds are fully up (0%) and DOWN means fully down (100%)
            // so we will update only position and not up or down states
        }
    }

    private LxControl getControlFromChannelUID(ChannelUID channelUID) {
        String channelId = channelUID.getIdWithoutGroup();
        return server.findControl(new LxUuid(channelId));
    }
}