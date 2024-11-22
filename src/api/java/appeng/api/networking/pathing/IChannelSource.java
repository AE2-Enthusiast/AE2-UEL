package appeng.api.networking.pathing;

import java.util.Set;

import appeng.api.networking.IGridHost;

/**
 * A source of channels in the network.
 *
 * An abstract version of the controller. The implementor is given the ability
 * to arbitrarily define what's considered a valid for this source. That could
 * range from the usual controller multiblock requirements to no requirements
 * whatsoever (allowing even multiple distinct sources).
 */
public interface IChannelSource extends IGridHost{
    /**
     * Whether or not this channel source has a valid shape
     *
     * <b>This is called once during network bootup on an arbitrary source.</b>
     * Make sure this properly handles multiple controllers on a network and/or
     * different channel source types.
     *
     * This is for ensuring certain network wide requirements are met for this
     * source to work. For example controllers can't have another unconnected
     * controller on the network and will prevent the network from forming at
     * all. Block specific requirements like controllers not allowing '+' shapes
     * should be done per block via disabling grid connections on that block.
     *
     * @param sources the sources in this grid, if you want to block formation
     * when multiple distinct sources exist for example
     * @return the state of the sources, <code>true</code> if it's
     * valid, <code>false</code> if it's invalid
     */
    boolean isValidShape(Set<IChannelSource> sources);
}
