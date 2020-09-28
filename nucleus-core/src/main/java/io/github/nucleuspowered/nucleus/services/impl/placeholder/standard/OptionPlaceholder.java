/*
 * This file is part of Nucleus, licensed under the MIT License (MIT). See the LICENSE.txt file
 * at the root of this project for more details.
 */
package io.github.nucleuspowered.nucleus.services.impl.placeholder.standard;

import io.github.nucleuspowered.nucleus.services.interfaces.IPermissionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.placeholder.PlaceholderContext;
import org.spongepowered.api.placeholder.PlaceholderParser;
import org.spongepowered.api.service.permission.Subject;

import java.util.Optional;

public class OptionPlaceholder implements PlaceholderParser {

    private final ResourceKey key = ResourceKey.resolve("nucleus:option");
    private final IPermissionService permissionService;

    public OptionPlaceholder(final IPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public Component parse(final PlaceholderContext placeholderContext) {
        final Optional<Subject> subjectOptional = placeholderContext.getAssociatedObject()
                .filter(x -> x instanceof Subject)
                .map(x -> (Subject) x);
        if (subjectOptional.isPresent() && placeholderContext.getArgumentString().isPresent()) {
            return this.permissionService
                    .getOptionFromSubject(subjectOptional.get(), placeholderContext.getArgumentString().get())
                    .map(LegacyComponentSerializer.legacyAmpersand()::deserialize)
                    .orElse(Component.empty());
        }
        return Component.empty();
    }

    @Override
    public ResourceKey getKey() {
        return this.key;
    }
}
