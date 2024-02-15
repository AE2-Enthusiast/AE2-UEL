/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.me.cache;

import appeng.core.AELog;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.*;
import appeng.api.networking.energy.*;
import appeng.api.networking.events.*;
import appeng.api.networking.events.MENetworkPowerStorage.PowerEventType;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
import appeng.me.GridNode;
import appeng.me.energy.EnergyThreshold;
import appeng.me.energy.EnergyWatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;

import java.util.*;


public class EnergyGridCache implements IEnergyGrid {

    private static final double MAX_BUFFER_STORAGE = 800;
    private final NavigableSet<EnergyThreshold> interests = Sets.newTreeSet();

    // Should only be modified from the add/remove methods below to guard against
    // concurrent modifications
    private final double averageLength = 40.0;
    private final Set<IAEPowerStorage> providers = new LinkedHashSet<>();
    // Used to track whether an extraction is currently in progress, to fail fast
    // when something externally
    // modifies the energy grid.
    private boolean ongoingExtractOperation = false;

    // Should only be modified from the add/remove methods below to guard against
    // concurrent modifications
    private final Set<IAEPowerStorage> requesters = new LinkedHashSet<>();
    // Used to track whether an injection is currently in progress, to fail fast
    // when something externally
    // modifies the energy grid.
    private boolean ongoingInjectOperation = false;

    private final HashMap<IGridNode, IEnergyWatcher> watchers = new HashMap<>();

    /**
     * real power available.
     */
    private int availableTicksSinceUpdate = 0;
    private double globalAvailablePower = 0;
    private double globalMaxPower = 0;
    private double amountInStorage = 0;

    /**
     * isCapped means that in this tick the power capped at either the max or
     * minimum (0)
     * wasCapped means that in the last tick the power had capped at either the
     * max or minimum
     */
    private boolean isCapped = false;
    private boolean wasCapped = false;

    /**
     * idle draw.
     */
    private double drainPerTick = 0;
    private double avgDrainPerTick = 0;
    private double avgInjectionPerTick = 0;
    private double tickDrainPerTick = 0;
    private double tickInjectionPerTick = 0;

    /**
     * power status
     */
    private boolean publicHasPower = false;
    private boolean hasPower = true;
    private long ticksSinceHasPowerChange = 900;

    private double lastStoredPower = -1;

    private final Set<IAEPowerStorage> providerToRemove = new HashSet<>();
    private final Set<IAEPowerStorage> requesterToRemove = new HashSet<>();
    private final Set<IAEPowerStorage> providersToAdd = new HashSet<>();
    private final Set<IAEPowerStorage> requesterToAdd = new HashSet<>();

    public EnergyGridCache() {
    }

    @MENetworkEventSubscribe
    public void nodeIdlePowerChangeHandler(final MENetworkPowerIdleChange ev) {
        // update power usage based on event.
        final GridNode node = (GridNode) ev.node;
        final IGridBlock gb = node.getGridBlock();

        final double newDraw = gb.getIdlePowerUsage();
        final double diffDraw = newDraw - node.getPreviousDraw();
        node.setPreviousDraw(newDraw);

        this.drainPerTick += diffDraw;
    }

    @MENetworkEventSubscribe
    public void storagePowerChangeHandler(final MENetworkPowerStorage ev) {
        if (ev.storage.isAEPublicPowerStorage()) {
            if (ev.type == PowerEventType.PROVIDE_POWER) {
                if (ev.storage.getPowerFlow() != AccessRestriction.WRITE) {
                    if (!ongoingExtractOperation) {
                        addProvider(ev.storage);
                    } else {
                        this.providersToAdd.add(ev.storage);
                    }
                }
            } else if (ev.type == PowerEventType.REQUEST_POWER) {
                if (ev.storage.getPowerFlow() != AccessRestriction.READ) {
                    if (!ongoingInjectOperation) {
                        addRequester(ev.storage);
                    } else {
                        this.requesterToAdd.add(ev.storage);
                    }
                }
            }
        } else {
            (new RuntimeException("Attempt to ask the IEnergyGrid to charge a non public energy store.")).printStackTrace();
        }
    }

    @Override
    public void onUpdateTick() {
        if (!this.isCapped) {
            this.writeToWorld(this.globalAvailablePower - this.amountInStorage);
        }
	this.wasCapped = this.isCapped;
	this.isCapped = false;
            
	if (!this.interests.isEmpty()) {
	    final double oldPower = this.lastStoredPower;
	    this.lastStoredPower = this.getStoredPower();
            final EnergyThreshold low = new EnergyThreshold(Math.min(oldPower, this.lastStoredPower), Integer.MIN_VALUE);
            final EnergyThreshold high = new EnergyThreshold(Math.max(oldPower, this.lastStoredPower), Integer.MAX_VALUE);

            for (final EnergyThreshold th : this.interests.subSet(low, true, high, true)) {
                ((EnergyWatcher) th.getEnergyWatcher()).post(this);
            }
        }

        this.avgDrainPerTick *= (this.averageLength - 1) / this.averageLength;
        this.avgInjectionPerTick *= (this.averageLength - 1) / this.averageLength;

        this.avgDrainPerTick += this.tickDrainPerTick / this.averageLength;
        this.avgInjectionPerTick += this.tickInjectionPerTick / this.averageLength;

        this.tickDrainPerTick = 0;
        this.tickInjectionPerTick = 0;

        // power information.
        boolean currentlyHasPower = false;

        if (this.drainPerTick > 0.0001) {
            final double drained = this.extractAEPower(this.getIdlePowerUsage(), Actionable.MODULATE, PowerMultiplier.CONFIG);
            currentlyHasPower = drained >= this.drainPerTick - 0.001;
        } else {
            currentlyHasPower = this.extractAEPower(0.1, Actionable.SIMULATE, PowerMultiplier.CONFIG) > 0;
        }

        // ticks since change..
        if (currentlyHasPower == this.hasPower) {
            this.ticksSinceHasPowerChange++;
        } else {
            this.ticksSinceHasPowerChange = 0;
        }

        // update status..
        this.hasPower = currentlyHasPower;

        // update public status, this buffers power ups for 30 ticks.
        if (this.hasPower && this.ticksSinceHasPowerChange > 30) {
            this.publicPowerState(true);
        } else if (!this.hasPower) {
            this.publicPowerState(false);
        }

        this.availableTicksSinceUpdate++;
    }

    @Override
    public double extractAEPower(final double amt, final Actionable mode, final PowerMultiplier pm) {
        final double toExtract = pm.multiply(amt);
        double extracted = this.extractProviderPower(toExtract, mode);
        return pm.divide(extracted);
    }

    @Override
    public double getIdlePowerUsage() {
        double draw = this.drainPerTick;
        for (IGrid grid : TickHandler.INSTANCE.getGridList()) {
            draw += ((PathGridCache)grid.getCache(IPathingGrid.class)).getChannelPowerUsage();
        }
        return draw;
    }

    private void publicPowerState(final boolean newState) {
        if (this.publicHasPower == newState) {
            return;
        }

        this.publicHasPower = newState;
        for (IGrid grid : TickHandler.INSTANCE.getGridList()) {
            ((Grid) grid).setImportantFlag(0, this.publicHasPower);
        
            grid.postEvent(new MENetworkPowerStatusChange());
        }
    }

    /**
     * refresh current stored power.
     */
    private void refreshPower() {
        this.availableTicksSinceUpdate = 0;
        this.globalAvailablePower = 0;
        for (final IAEPowerStorage p : this.providers) {
            this.globalAvailablePower += p.getAECurrentPower();
        }
    }

    @Override
    public Collection<IEnergyGridProvider> providers() {
        return Collections.singletonList(this);
    }

    @Override
    public double extractProviderPower(final double amt, final Actionable mode) {
	double extracted = Math.min(amt, this.globalAvailablePower);

        if (mode == Actionable.MODULATE) {
            this.globalAvailablePower -= extracted;
            this.tickDrainPerTick += extracted;
            if (this.globalAvailablePower <= 0) {
                if (this.amountInStorage >= 0) {
		    this.writeToWorld(-this.amountInStorage);
		}
		this.isCapped = true;
            }
        }

        
        return extracted;
    }

    @Override
    public double injectProviderPower(double amt, final Actionable mode) {
	double toStore = Math.min(amt, this.globalMaxPower - this.globalAvailablePower);

        if (mode == Actionable.MODULATE) {
            this.globalAvailablePower += toStore;
            this.tickInjectionPerTick += toStore;
            if (this.globalAvailablePower >= this.globalMaxPower) {
                if (this.amountInStorage <= this.globalMaxPower) {
		    this.writeToWorld(this.globalMaxPower - this.amountInStorage);
		}
		this.isCapped = true;
            }
        }

        

        return amt - toStore;
    }

    @Override
    public double getProviderEnergyDemand(final double maxRequired) {
        return this.globalMaxPower - this.globalAvailablePower;
    }

    @Override
    public double getAvgPowerUsage() {
        return this.avgDrainPerTick;
    }

    @Override
    public double getAvgPowerInjection() {
        return this.avgInjectionPerTick;
    }

    @Override
    public boolean isNetworkPowered() {
        return this.publicHasPower;
    }

    @Override
    public double injectPower(final double amt, final Actionable mode) {
        double leftover = this.injectProviderPower(amt, mode);
        return leftover;
    }

    @Override
    public double getStoredPower() {
        return Math.max(0.0, this.globalAvailablePower);
    }

    @Override
    public double getMaxStoredPower() {
        return this.globalMaxPower;
    }

    @Override
    public double getEnergyDemand(final double maxRequired) {
        return this.getProviderEnergyDemand(maxRequired);
    }

    @Override
    public double getProviderStoredEnergy() {
        return this.getStoredPower();
    }

    @Override
    public double getProviderMaxEnergy() {
        return this.getMaxStoredPower();
    }

    @Override
    public void removeNode(final IGridNode node, final IGridHost machine) {
        // idle draw.
        final GridNode gridNode = (GridNode) node;
        this.drainPerTick -= gridNode.getPreviousDraw();

        // power storage.
        if (machine instanceof IAEPowerStorage) {
            final IAEPowerStorage ps = (IAEPowerStorage) machine;
            if (ps.isAEPublicPowerStorage()) {
                if (ps.getPowerFlow() != AccessRestriction.WRITE) {
		    this.isCapped = false;
                    this.globalMaxPower -= ps.getAEMaxPower();
		    double current = ps.getAECurrentPower();
                    this.globalAvailablePower -= current;
		    this.amountInStorage -= current;
                }
                if (!ongoingExtractOperation) {
                    removeProvider(ps);
                } else {
                    this.providerToRemove.add(ps);
                }
                if (!ongoingInjectOperation) {
                    removeRequester(ps);
                } else {
                    this.requesterToRemove.add(ps);
                }
            }
        }

        if (machine instanceof IEnergyWatcherHost) {
            final IEnergyWatcher watcher = this.watchers.get(node);

            if (watcher != null) {
                watcher.reset();
                this.watchers.remove(node);
            }
        }
    }

    private void addRequester(IAEPowerStorage requester) {
        Preconditions.checkState(!ongoingInjectOperation, "Cannot modify energy requesters while energy is being injected.");
        this.requesters.add(requester);
    }

    private void removeRequester(IAEPowerStorage requester) {
        Preconditions.checkState(!ongoingInjectOperation, "Cannot modify energy requesters while energy is being injected.");
        this.requesters.remove(requester);
    }

    private void addProvider(IAEPowerStorage provider) {
        Preconditions.checkState(!ongoingExtractOperation, "Cannot modify energy providers while energy is being extracted.");
        this.providers.add(provider);
    }

    private void removeProvider(IAEPowerStorage provider) {
        Preconditions.checkState(!ongoingExtractOperation, "Cannot modify energy providers while energy is being extracted.");
        this.providers.remove(provider);
    }


    @Override
    public void addNode(final IGridNode node, final IGridHost machine) {
        // idle draw...
        final GridNode gridNode = (GridNode) node;
        final IGridBlock gb = gridNode.getGridBlock();
        gridNode.setPreviousDraw(gb.getIdlePowerUsage());
        this.drainPerTick += gridNode.getPreviousDraw();

        // power storage
        if (machine instanceof IAEPowerStorage) {
            final IAEPowerStorage ps = (IAEPowerStorage) machine;
            if (ps.isAEPublicPowerStorage()) {
                
                final double max = ps.getAEMaxPower();
                final double current = ps.getAECurrentPower();

                if (ps.getPowerFlow() != AccessRestriction.WRITE) {
		    this.isCapped = false;
                    this.globalMaxPower += ps.getAEMaxPower();
                }

                if (current > 0 && ps.getPowerFlow() != AccessRestriction.WRITE) {
                    this.globalAvailablePower += current;
		    this.amountInStorage += current;
                    if (!ongoingExtractOperation) {
                        addProvider(ps);
                    } else {
                        this.providersToAdd.add(ps);
                    }
                }

                if (current < max && ps.getPowerFlow() != AccessRestriction.READ) {
                    if (!ongoingInjectOperation) {
                        addRequester(ps);
                    } else {
                        this.requesterToAdd.add(ps);
                    }
                }
            }
        }

        if (machine instanceof IEnergyWatcherHost) {
            final IEnergyWatcherHost swh = (IEnergyWatcherHost) machine;
            final EnergyWatcher iw = new EnergyWatcher(this, swh);

            this.watchers.put(node, iw);
            swh.updateWatcher(iw);
        }

        //for (IGrid grid : TickHandler.INSTANCE.getGridList()) {
        //    grid.postEventTo(node, new MENetworkPowerStatusChange());
        //}

        node.getGrid().postEventTo(node, new MENetworkPowerStatusChange());
    }

    private void writeToWorld(double amt) {
	double overflow = 0;
	this.amountInStorage += amt;
	    
	this.requesters.addAll(requesterToAdd);
	this.requesterToAdd.clear();
	this.requesters.removeAll(requesterToRemove);
	this.requesterToRemove.clear();

	this.providers.addAll(providersToAdd);
	this.providersToAdd.clear();
	this.providers.removeAll(providerToRemove);
	this.providerToRemove.clear();

	if (amt > 0) {
	    this.ongoingInjectOperation = true;
            Iterator<IAEPowerStorage> it = this.requesters.iterator();
            while (it.hasNext()) {
                IAEPowerStorage requester = it.next();
		amt = requester.injectAEPower(amt, Actionable.MODULATE);
		if (amt <= 0) {
		    break;
		} else { //requester couldn't take it all, must be full
                    it.remove();
                }
            }
	    this.ongoingInjectOperation = false;
            if (amt != 0) {
                AELog.debug("EnergyGrid requesters underreporting? " + amt + " AE couldn't fit in storage");
            }
	} else if (amt < 0) {
	    amt = -amt;
	    this.ongoingExtractOperation = true;
            Iterator<IAEPowerStorage> it = this.providers.iterator();
            while (it.hasNext()) {
                IAEPowerStorage provider = it.next();
		amt -= provider.extractAEPower(amt, Actionable.MODULATE, PowerMultiplier.ONE);
		if (amt <= 0) {
		    break;
		} else { //provider couldn't give enough, must be empty
                    it.remove();
                }
            }
	    this.ongoingExtractOperation = false;
            if (amt != 0) {
                AELog.debug("EnergyGrid providers overreporting? " + amt + " AE couldn't be extracted");
            }
	}
    }

    @Override
    public void onSplit(final IGridStorage storageB) {
    }

    @Override
    public void onJoin(final IGridStorage storageB) {
    }

    @Override
    public void populateGridStorage(final IGridStorage storage) {
    }

    public boolean registerEnergyInterest(final EnergyThreshold threshold) {
        return this.interests.add(threshold);
    }

    public boolean unregisterEnergyInterest(final EnergyThreshold threshold) {
        return this.interests.remove(threshold);
    }

}
