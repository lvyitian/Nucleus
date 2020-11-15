/*
 * This file is part of Nucleus, licensed under the MIT License (MIT). See the LICENSE.txt file
 * at the root of this project for more details.
 */
package io.github.nucleuspowered.nucleus.services.impl.docgen;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.nucleuspowered.nucleus.core.docgen.CommandDoc;
import io.github.nucleuspowered.nucleus.core.docgen.EssentialsDoc;
import io.github.nucleuspowered.nucleus.core.docgen.PermissionDoc;
import io.github.nucleuspowered.nucleus.core.docgen.TokenDoc;
import io.github.nucleuspowered.nucleus.scaffold.command.annotation.Command;
import io.github.nucleuspowered.nucleus.scaffold.command.annotation.CommandModifier;
import io.github.nucleuspowered.nucleus.scaffold.command.annotation.EssentialsEquivalent;
import io.github.nucleuspowered.nucleus.scaffold.command.control.CommandControl;
import io.github.nucleuspowered.nucleus.scaffold.command.control.CommandMetadata;
import io.github.nucleuspowered.nucleus.scaffold.command.modifier.CommandModifiers;
import io.github.nucleuspowered.nucleus.services.INucleusServiceCollection;
import io.github.nucleuspowered.nucleus.services.impl.placeholder.PlaceholderMetadata;
import io.github.nucleuspowered.nucleus.services.interfaces.ICommandMetadataService;
import io.github.nucleuspowered.nucleus.services.interfaces.IDocumentationGenerationService;
import io.github.nucleuspowered.nucleus.services.interfaces.IMessageProviderService;
import io.github.nucleuspowered.nucleus.services.interfaces.IPermissionService;
import io.github.nucleuspowered.nucleus.services.interfaces.data.SuggestedLevel;
import io.leangen.geantyref.TypeToken;
import net.kyori.adventure.text.Component;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandCause;
import org.spongepowered.api.event.CauseStackManager;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
public class DocumentationGenerationService implements IDocumentationGenerationService {

    private final static TypeToken<List<CommandDoc>> COMMAND_DOC_LIST_TYPE_TOKEN = new TypeToken<List<CommandDoc>>() {};
    private final static TypeToken<List<PermissionDoc>> PERMISSION_DOC_LIST_TYPE_TOKEN = new TypeToken<List<PermissionDoc>>() {};
    private final static TypeToken<List<TokenDoc>> TOKEN_DOC_LIST_TYPE_TOKEN = new TypeToken<List<TokenDoc>>() {};
    private final static TypeToken<List<EssentialsDoc>> ESSENTIALS_DOC_LIST_TYPE_TOKEN = new TypeToken<List<EssentialsDoc>>() {};

    private final INucleusServiceCollection serviceCollection;

    @Inject
    public DocumentationGenerationService(final INucleusServiceCollection serviceCollection) {
        this.serviceCollection = serviceCollection;
    }

    @Override
    public void generate(final Path directory) throws IOException {
        try (final CauseStackManager.StackFrame stackFrame = Sponge.getServer().getCauseStackManager().pushCauseFrame()) {
            stackFrame.pushCause(Sponge.getSystemSubject());
            final CommandCause cause = CommandCause.create();

            final ICommandMetadataService commandMetadataService = this.serviceCollection.commandMetadataService();
            final IPermissionService permissionService = this.serviceCollection.permissionService();
            final IMessageProviderService messageProviderService = this.serviceCollection.messageProvider();
            final Collection<CommandControl> commands = commandMetadataService.getCommandsAndSubcommands();

            final List<EssentialsDoc> essentialsDocs = new ArrayList<>();
            final List<CommandDoc> lcd = this.getAndSort(
                    commands,
                    (CommandControl first, CommandControl second) -> {
                        final int m = first.getMetadata().getModuleid().compareToIgnoreCase(second.getMetadata().getModuleid());
                        if (m == 0) {
                            return first.getCommandKey().compareToIgnoreCase(second.getCommandKey());
                        }

                        return m;
                    },
                    control -> {
                        final CommandMetadata metadata = control.getMetadata();
                        final CommandDoc commandDoc = new CommandDoc();
                        final String cmdPath = metadata.getCommandKey().replaceAll("\\.", " ");
                        commandDoc.setCommandName(cmdPath);
                        commandDoc.setModule(metadata.getModuleid());

                        if (metadata.isRoot()) {
                            commandDoc.setAliases(String.join(", ", metadata.getAliases()));
                        } else {
                            final String key = metadata.getCommandKey()
                                    .replaceAll("\\.[a-z]+$", " ")
                                    .replaceAll("\\.", " ");
                            commandDoc.setAliases(key + Arrays.stream(metadata.getAliases())
                                    .filter(x -> !x.startsWith("#"))
                                    .map(x -> x.replace("^$", ""))
                                    .collect(Collectors.joining(", " + key)));
                            if (!metadata.getRootAliases().isEmpty()) {
                                commandDoc.setRootAliases(String.join(", ", metadata.getRootAliases()));
                            }
                        }

                        final Set<PermissionDoc> permissionDocs = new HashSet<>();
                        final Command annotation = metadata.getCommandAnnotation();
                        for (final CommandModifier modifier : annotation.modifiers()) {
                            switch (modifier.value()) {
                                case CommandModifiers.HAS_COOLDOWN:
                                    commandDoc.setCooldown(true);
                                    break;
                                case CommandModifiers.HAS_COST:
                                    commandDoc.setCost(true);
                                    break;
                                case CommandModifiers.HAS_WARMUP:
                                    commandDoc.setWarmup(true);
                                    break;
                            }
                            this.getPermissionDoc(modifier.exemptPermission()).ifPresent(permissionDocs::add);
                        }

                        for (final String perm : metadata.getCommandAnnotation().associatedPermissions()) {
                            this.getPermissionDoc(perm).ifPresent(permissionDocs::add);
                        }

                        final EssentialsEquivalent essentialsEquivalent = metadata.getEssentialsEquivalent();
                        if (essentialsEquivalent != null) {
                            final List<String> eqiv = Arrays.asList(essentialsEquivalent.value());
                            commandDoc.setEssentialsEquivalents(eqiv);
                            commandDoc.setEssNotes(essentialsEquivalent.notes());
                            commandDoc.setExactEssEquiv(essentialsEquivalent.isExact());
                            essentialsDocs.add(
                                    new EssentialsDoc()
                                            .setExact(essentialsEquivalent.isExact())
                                            .setNotes(essentialsEquivalent.notes())
                                            .setEssentialsCommands(eqiv)
                                            .setNucleusEquiv(metadata.getRootAliases())
                            );
                        }

                        final String[] base = metadata.getCommandAnnotation().basePermission();
                        SuggestedLevel level = SuggestedLevel.USER;
                        if (base.length > 0) {
                            commandDoc.setPermissionbase(base[0]);
                            for (final String permission : base) {
                                final Optional<IPermissionService.Metadata> pm =
                                        this.serviceCollection.permissionService().getMetadataFor(permission);
                                if (pm.isPresent()) {
                                    if (pm.get().getSuggestedLevel().compareTo(level) > 0) {
                                        level = pm.get().getSuggestedLevel();
                                    }
                                    permissionDocs.add(this.getFor(pm.get()));
                                }
                            }
                        }

                        commandDoc.setDefaultLevel(level.getRole());
                        commandDoc.setOneLineDescription(control.getShortDescription(cause)
                                .map(Component::toString).orElse("No description provided"));
                        commandDoc.setExtendedDescription(control.getExtendedDescription(cause)
                                .map(Component::toString)
                                .orElse(null));

                        // TODO: Usage
                        // commandDoc.setUsageString(control.getUsage().toPlain());
                        commandDoc.setPermissions(new ArrayList<>(permissionDocs));
                        // commandDoc.setSimpleUsage(control.getUsageText(cause).toPlain());
                        commandDoc.setContext(control.getContext().getValue());

                        return commandDoc;
                    });

            final List<PermissionDoc> permdocs = permissionService.getAllMetadata()
                    .stream()
                    .map(this::getFor)
                    .filter(x -> x.getPermission() != null)
                    .sorted(Comparator.comparing(PermissionDoc::getPermission))
                    .collect(Collectors.toList());

            final List<TokenDoc> tokenDocs = this.serviceCollection
                    .placeholderService()
                    .getNucleusParsers()
                    .values()
                    .stream()
                    .filter(PlaceholderMetadata::isDocument)
                    .filter(x -> {
                        if (!messageProviderService.hasKey("nucleus.token." + x.getToken().toLowerCase())) {
                            this.serviceCollection.logger().warn("Could not find message key for nucleus.token.{}", x.getToken().toLowerCase());
                            return false;
                        }
                        return true;
                    })
                    .map(x -> new TokenDoc()
                            .setId(x.getParser().getKey().asString())
                            .setName(x.getToken())
                            .setDescription(messageProviderService.getMessageString("nucleus.token." + x.getToken().toLowerCase())))
                    .collect(Collectors.toList());

            final YamlConfigurationLoader configurationLoader = YamlConfigurationLoader.builder()
                    .path(directory.resolve("commands.yml"))
                    .nodeStyle(NodeStyle.BLOCK)
                    .build();

            // Now do all the saving
            // Config files
            final Map<String, Class<?>> configs = this.serviceCollection
                    .configProvider()
                    .getModuleToConfigType();
            final ConfigurationNode configNode = configurationLoader.createNode();
            for (final Map.Entry<String, Class<?>> entry : configs.entrySet()) {
                try {
                    configNode.node(entry.getKey()).set(this.createConfigString(entry.getValue().newInstance()));
                } catch (final InstantiationException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            }

            // Generate command file.
            final ConfigurationNode commandConfigurationNode = configurationLoader.createNode().set(COMMAND_DOC_LIST_TYPE_TOKEN, lcd);
            configurationLoader.save(commandConfigurationNode);

            final YamlConfigurationLoader permissionsConfigurationLoader = YamlConfigurationLoader.builder()
                    .path(directory.resolve("permissions.yml"))
                    .nodeStyle(NodeStyle.BLOCK)
                    .build();
            final ConfigurationNode permissionConfiguationNode = configurationLoader.createNode().set(PERMISSION_DOC_LIST_TYPE_TOKEN, permdocs);
            permissionsConfigurationLoader.save(permissionConfiguationNode);

            final YamlConfigurationLoader essentialsConfigurationLoader = YamlConfigurationLoader.builder()
                    .path(directory.resolve("essentials.yml"))
                    .nodeStyle(NodeStyle.BLOCK)
                    .build();
            final ConfigurationNode essentialsConfigurationNode = configurationLoader.createNode().set(ESSENTIALS_DOC_LIST_TYPE_TOKEN,
                    essentialsDocs);
            essentialsConfigurationLoader.save(essentialsConfigurationNode);

            final YamlConfigurationLoader configurationConfigurationLoader = YamlConfigurationLoader.builder()
                    .path(directory.resolve("conf.yml"))
                    .nodeStyle(NodeStyle.BLOCK)
                    .build();
            configurationConfigurationLoader.save(configNode);

            final YamlConfigurationLoader tokensConfigurationLoader = YamlConfigurationLoader.builder()
                    .path(directory.resolve("tokens.yml"))
                    .nodeStyle(NodeStyle.BLOCK)
                    .build();
            final ConfigurationNode tokensConfigNode = configurationLoader.createNode().set(TOKEN_DOC_LIST_TYPE_TOKEN, tokenDocs);
            tokensConfigurationLoader.save(tokensConfigNode);
        }
    }

    private <T, R> List<R> getAndSort(
            final Collection<T> list,
            final Comparator<T> comparator,
            final Function<T, R> mapper) {
        return list.stream().sorted(comparator).map(mapper).collect(Collectors.toList());
    }

    private Optional<PermissionDoc> getPermissionDoc(final String permission) {
        return this.serviceCollection.permissionService().getMetadataFor(permission).map(this::getFor);
    }

    private PermissionDoc getFor(final IPermissionService.Metadata metadata) {
        return new PermissionDoc()
                .setDefaultLevel(metadata.getSuggestedLevel().getRole())
                .setDescription(metadata.getDescription(this.serviceCollection.messageProvider()))
                .setPermission(metadata.getPermission())
                .setModule(metadata.getModuleId());
    }

    private String createConfigString(final Object obj) throws IOException {
        try (final StringWriter sw = new StringWriter(); final BufferedWriter writer = new BufferedWriter(sw)) {
            final HoconConfigurationLoader hcl = HoconConfigurationLoader.builder()
                    .defaultOptions(this.serviceCollection.configurateHelper().getOptions())
                    .sink(() -> writer)
                    .build();
            final CommentedConfigurationNode cn = hcl.createNode(this.serviceCollection.configurateHelper().getOptions());
            this.applyToNode(obj.getClass(), obj, cn);
            hcl.save(cn);
            return sw.toString();
        }
    }

    private <T> void applyToNode(final Class<T> c, final Object object, final ConfigurationNode node) {
        try {
            node.set(TypeToken.get(c), c.cast(object));
        } catch (final SerializationException e) {
            e.printStackTrace();
        }
    }
}
