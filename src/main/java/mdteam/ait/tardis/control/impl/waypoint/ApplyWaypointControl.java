package mdteam.ait.tardis.control.impl.waypoint;

import mdteam.ait.tardis.Tardis;
import mdteam.ait.tardis.control.Control;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

public class ApplyWaypointControl extends Control {
    public ApplyWaypointControl() {
        super("apply_waypoint");
    }

    @Override
    public boolean runServer(Tardis tardis, ServerPlayerEntity player, ServerWorld world) {
        tardis.getHandlers().getWaypoints().setDestination();
        return true;
    }
}
