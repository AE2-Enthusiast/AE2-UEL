package appeng.api.networking.pathing;

import java.util.Set;

import appeng.api.networking.IGridHost;

public interface IChannelSource extends IGridHost{
    /**
     * Whether or not this channel source has a valid shape
     *
     * <b>This is called once during network bootup on an arbitrary source.</b> Make sure this properly handles multiple controllers on a network and/or a different channel source.
     */
    ControllerState isValidShape(Set<IChannelSource> sources);
}
