/*
 * This file is part of Nucleus, licensed under the MIT License (MIT). See the LICENSE.txt file
 * at the root of this project for more details.
 */
package io.github.nucleuspowered.nucleus.modules.teleport.commands;

import com.google.inject.Inject;
import io.github.nucleuspowered.nucleus.argumentparsers.NicknameArgument;
import io.github.nucleuspowered.nucleus.argumentparsers.SelectorWrapperArgument;
import io.github.nucleuspowered.nucleus.dataservices.modular.ModularUserService;
import io.github.nucleuspowered.nucleus.internal.annotations.NoCooldown;
import io.github.nucleuspowered.nucleus.internal.annotations.NoCost;
import io.github.nucleuspowered.nucleus.internal.annotations.NoWarmup;
import io.github.nucleuspowered.nucleus.internal.annotations.Permissions;
import io.github.nucleuspowered.nucleus.internal.annotations.RegisterCommand;
import io.github.nucleuspowered.nucleus.internal.command.AbstractCommand;
import io.github.nucleuspowered.nucleus.internal.command.ReturnMessageException;
import io.github.nucleuspowered.nucleus.internal.docgen.annotations.EssentialsEquivalent;
import io.github.nucleuspowered.nucleus.internal.permissions.PermissionInformation;
import io.github.nucleuspowered.nucleus.internal.permissions.SuggestedLevel;
import io.github.nucleuspowered.nucleus.modules.core.datamodules.CoreUserDataModule;
import io.github.nucleuspowered.nucleus.modules.teleport.config.TeleportConfigAdapter;
import io.github.nucleuspowered.nucleus.modules.teleport.handlers.TeleportHandler;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.CommandElement;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.text.Text;

import java.util.HashMap;
import java.util.Map;

/**
 * NOTE: TeleportHere is considered an admin command, as there is a potential
 * for abuse for non-admin players trying to pull players. No cost or warmups
 * will be applied. /tpahere should be used instead in these circumstances.
 */
@Permissions(prefix = "teleport", suggestedLevel = SuggestedLevel.ADMIN, supportsSelectors = true)
@NoWarmup
@NoCooldown
@NoCost
@RegisterCommand({"tphere", "tph"})
@EssentialsEquivalent(value = {"tphere", "s", "tpohere"}, isExact = false,
        notes = "If you have permission, this will override '/tptoggle' automatically.")
public class TeleportHereCommand extends AbstractCommand<Player> {

    private final String playerKey = "subject";
    private final String quietKey = "quiet";

    @Inject private TeleportHandler handler;
    @Inject private TeleportConfigAdapter tca;

    @Override
    public Map<String, PermissionInformation> permissionSuffixesToRegister() {
        Map<String, PermissionInformation> m = new HashMap<>();
        m.put("offline", PermissionInformation.getWithTranslation("permission.teleport.offline", SuggestedLevel.ADMIN));
        return m;
    }

    @Override
    public CommandElement[] getArguments() {
        return new CommandElement[] {
            GenericArguments.onlyOne(
                SelectorWrapperArgument.nicknameSelector(Text.of(playerKey), NicknameArgument.UnderlyingType.USER)
            )
        };
    }

    @Override
    public CommandResult executeCommand(Player src, CommandContext args) throws Exception {
        boolean beQuiet = args.<Boolean>getOne(quietKey).orElse(tca.getNodeOrDefault().isDefaultQuiet());
        User target = args.<User>getOne(playerKey).get();
        if (target.getPlayer().isPresent()) {
            handler.getBuilder().setFrom(target.getPlayer().get()).setTo(src).setSilentSource(beQuiet).startTeleport();
        } else {
            permissions.checkSuffix(src, "offline", () -> ReturnMessageException.fromKey("command.tphere.noofflineperms"));

            // Update the offline player's next location
            ModularUserService mus = plugin.getUserDataManager().get(target)
                    .orElseThrow(() -> ReturnMessageException.fromKey("command.tphere.couldnotset", target.getName()));
            mus.get(CoreUserDataModule.class).sendToLocationOnLogin(src.getLocation());
            mus.save();

            src.sendMessage(plugin.getMessageProvider().getTextMessageWithFormat("command.tphere.offlinesuccess", target.getName()));
        }

        return CommandResult.success();
    }
}
