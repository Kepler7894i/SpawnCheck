package dev.spawncheck.fake;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /spawncheckfake [<pos> | remove]}: the server-side command for placing and removing fake players (operators only). */
public final class FakeCommand {
	private FakeCommand() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build() {
		return Commands.literal("spawncheckfake")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.executes(c -> placeFake(c.getSource(), BlockPos.containing(c.getSource().getPosition())))
			.then(Commands.literal("remove").executes(c -> {
				int removed = FakePlayers.removeAll();
				c.getSource().sendSuccess(() -> Component.literal("Removed " + removed + " fake player(s)"), false);
				return removed;
			}))
			.then(Commands.argument("pos", BlockPosArgument.blockPos())
				.executes(c -> placeFake(c.getSource(), BlockPosArgument.getBlockPos(c, "pos"))));
	}

	private static int placeFake(final CommandSourceStack source, final BlockPos pos) {
		ServerPlayer fake = FakePlayers.spawn(source.getLevel(), pos);
		source.sendSuccess(() -> Component.literal("Placed fake player " + fake.getGameProfile().name() + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()), false);
		return 1;
	}
}
