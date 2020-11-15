/*
 * This file is part of Nucleus, licensed under the MIT License (MIT). See the LICENSE.txt file
 * at the root of this project for more details.
 */
package io.github.nucleuspowered.nucleus;

import com.google.inject.Injector;
import io.github.nucleuspowered.nucleus.api.core.NucleusUserPreferenceService;
import io.github.nucleuspowered.nucleus.api.teleport.data.TeleportScanner;
import io.github.nucleuspowered.nucleus.core.CoreModule;
import io.github.nucleuspowered.nucleus.core.CorePermissions;
import io.github.nucleuspowered.nucleus.core.config.CoreConfig;
import io.github.nucleuspowered.nucleus.core.services.UniqueUserService;
import io.github.nucleuspowered.nucleus.core.teleport.filters.NoCheckFilter;
import io.github.nucleuspowered.nucleus.core.teleport.filters.WallCheckFilter;
import io.github.nucleuspowered.nucleus.core.teleport.scanners.NoTeleportScanner;
import io.github.nucleuspowered.nucleus.core.teleport.scanners.VerticalTeleportScanner;
import io.github.nucleuspowered.nucleus.guice.NucleusInjectorModule;
import io.github.nucleuspowered.nucleus.module.IModule;
import io.github.nucleuspowered.nucleus.module.IModuleProvider;
import io.github.nucleuspowered.nucleus.module.ModuleContainer;
import io.github.nucleuspowered.nucleus.module.ModuleEvent;
import io.github.nucleuspowered.nucleus.scaffold.command.modifier.CommandModifierFactory;
import io.github.nucleuspowered.nucleus.scaffold.command.modifier.CommandModifiers;
import io.github.nucleuspowered.nucleus.scaffold.command.modifier.impl.CooldownModifier;
import io.github.nucleuspowered.nucleus.scaffold.command.modifier.impl.CostModifier;
import io.github.nucleuspowered.nucleus.scaffold.command.modifier.impl.RequiresEconomyModifier;
import io.github.nucleuspowered.nucleus.scaffold.command.modifier.impl.WarmupModifier;
import io.github.nucleuspowered.nucleus.scaffold.listener.ListenerBase;
import io.github.nucleuspowered.nucleus.scaffold.listener.ListenerReloadableWrapper;
import io.github.nucleuspowered.nucleus.scaffold.task.SyncTaskBase;
import io.github.nucleuspowered.nucleus.scaffold.task.TaskBase;
import io.github.nucleuspowered.nucleus.services.INucleusServiceCollection;
import io.github.nucleuspowered.nucleus.services.impl.NucleusServiceCollection;
import io.github.nucleuspowered.nucleus.services.impl.userprefs.NucleusKeysProvider;
import io.github.nucleuspowered.nucleus.services.impl.userprefs.PreferenceKeyImpl;
import io.github.nucleuspowered.nucleus.services.interfaces.IConfigProvider;
import io.github.nucleuspowered.nucleus.services.interfaces.IReloadableService;
import io.github.nucleuspowered.nucleus.services.interfaces.IStorageManager;
import io.github.nucleuspowered.nucleus.startuperror.ConfigErrorHandler;
import io.github.nucleuspowered.nucleus.util.functional.Action;
import io.github.nucleuspowered.storage.persistence.IStorageRepositoryFactory;
import io.leangen.geantyref.TypeToken;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.event.Cause;
import org.spongepowered.api.event.EventContext;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.lifecycle.RefreshGameEvent;
import org.spongepowered.api.event.lifecycle.RegisterCatalogEvent;
import org.spongepowered.api.event.lifecycle.RegisterCatalogRegistryEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.RegisterFactoryEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StartingEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.placeholder.PlaceholderParser;
import org.spongepowered.api.scheduler.ScheduledTask;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.util.Tuple;
import org.spongepowered.api.world.teleport.TeleportHelperFilter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.transformation.ConfigurationTransformation;
import org.spongepowered.plugin.PluginContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class NucleusCore {

    public static final String DOCGEN_PROPERTY = "nucleus.docgen";

    private final PluginContainer pluginContainer;
    private final Path configDirectory;
    private final Logger logger;
    private final Injector injector;
    private final IModuleProvider provider;
    private final boolean runDocGen = System.getProperty(DOCGEN_PROPERTY) != null;
    private final List<Action> onStartedActions = new LinkedList<>();
    private final IPluginInfo pluginInfo;

    @Nullable private Path dataDirectory;

    private final INucleusServiceCollection serviceCollection;

    public NucleusCore(
            final PluginContainer pluginContainer,
            final Path configDirectory,
            final Logger logger,
            final Injector injector,
            final IModuleProvider provider,
            final IPluginInfo pluginInfo) {
        this.pluginContainer = pluginContainer;
        this.configDirectory = configDirectory;
        this.logger = logger;
        this.pluginInfo = pluginInfo;
        this.injector = injector.createChildInjector(new NucleusInjectorModule(() -> this, pluginInfo));
        this.provider = provider;
        this.serviceCollection = new NucleusServiceCollection(
                this.injector,
                this.pluginContainer,
                this.logger,
                this::getDataDirectory,
                this.configDirectory
        );
    }

    /**
     * Begin setup, read module config, load modules that are important.
     */
    public void init() {
        final Collection<Tuple<ModuleContainer, IModule>> tuple = this.startModuleLoading();
        this.serviceCollection.configurateHelper().complete();
        final IConfigProvider provider = this.serviceCollection.configProvider();
        try {
            provider.prepareCoreConfig(this.coreConfigurationTransformations());
        } catch (final ConfigurateException e) {
            new ConfigErrorHandler(this.pluginContainer, e, this.runDocGen, this.logger, provider.getCoreConfigFileName(), this.pluginInfo);
        }
        try {
            provider.prepareModuleConfig();
        } catch (final ConfigurateException e) {
            new ConfigErrorHandler(this.pluginContainer, e, this.runDocGen, this.logger, provider.getModuleConfigFileName(), this.pluginInfo);
        }
        this.completeModuleInit(tuple);
        this.serviceCollection.userPreferenceService().postInit();
    }

    public INucleusServiceCollection getServiceCollection() {
        return this.serviceCollection;
    }

    public Path getDataDirectory() {
        if (this.dataDirectory == null) {
            this.dataDirectory = Sponge.getServer().getGame().getGameDirectory().resolve("nucleus");
            try {
                Files.createDirectories(this.dataDirectory);
            } catch (final IOException e) {
                this.logger.fatal("Could not create directory '" + this.dataDirectory.toAbsolutePath() + "'", e);
            }
        }

        return this.dataDirectory;
    }

    public Path getConfigDirectory() {
        return this.configDirectory;
    }

    @Listener
    public void establishNewRegistries(final RegisterCatalogRegistryEvent event) {
        event.register(CommandModifierFactory.class, ResourceKey.of("nucleus", "command_modifier_factory"));
        event.register(TeleportScanner.class, ResourceKey.of("nucleus", "teleport_scanner"));
        event.register(IStorageRepositoryFactory.class, ResourceKey.of("nucleus", "storage_repository_factory"));
        event.register(NucleusUserPreferenceService.PreferenceKey.class, ResourceKey.of("nucleus", "user_preference_key"));
    }

    @Listener
    public void registerCommandModifierFactories(final RegisterCatalogEvent<CommandModifierFactory> event) {
        event.register(new CommandModifierFactory.Simple(CommandModifiers.HAS_COOLDOWN, new CooldownModifier()));
        event.register(new CommandModifierFactory.Simple(CommandModifiers.HAS_COST, new CostModifier()));
        event.register(new CommandModifierFactory.Simple(CommandModifiers.HAS_WARMUP, new WarmupModifier()));
        event.register(new CommandModifierFactory.Simple(CommandModifiers.REQUIRE_ECONOMY, new RequiresEconomyModifier()));
    }

    @Listener
    public void registerTeleportScanners(final RegisterCatalogEvent<TeleportScanner> event) {
        event.register(new NoTeleportScanner());
        event.register(new VerticalTeleportScanner.Ascending());
        event.register(new VerticalTeleportScanner.Descending());
    }

    @Listener
    public void onRegisterTeleportHelperFilters(final RegisterCatalogEvent<TeleportHelperFilter> event) {
        event.register(new NoCheckFilter());
        event.register(new WallCheckFilter());
    }

    @Listener
    public void onRegisterPlaceholders(final RegisterCatalogEvent<PlaceholderParser> event) {
        this.serviceCollection.placeholderService().getParsers().forEach(event::register);
        this.serviceCollection.logger().info("Registered placeholder parsers.");
    }

    @Listener
    public void registerStorageRepositoryFactories(final RegisterCatalogEvent<IStorageRepositoryFactory> event) {
        event.register(this.serviceCollection.storageManager().getFlatFileRepositoryFactory());
    }

    @Listener
    public void registerCoreUserPreferenceKeys(final RegisterCatalogEvent<NucleusUserPreferenceService.PreferenceKey<?>> event) {
        event.register(
                new PreferenceKeyImpl.LocaleKey(
                        NucleusKeysProvider.PLAYER_LOCALE_KEY,
                        Locale.UK,
                        CorePermissions.BASE_NUCLEUSLANGUAGE,
                        "userpref.player_locale",
                        (serviceCollection, uuid, value) -> serviceCollection.messageProvider().invalidateLocaleCacheFor(uuid)
                )
        );
    }

    @Listener
    public void establishFactories(final RegisterFactoryEvent event) {
        this.serviceCollection.registerFactories(event);
    }

    @Listener(order = Order.LAST)
    public void establishCommands(final RegisterCommandEvent<Command.Parameterized> event) {
        this.serviceCollection.commandMetadataService().completeRegistrationPhase(this.serviceCollection, event);
    }

    @Listener
    public void serverStarting(final StartingEngineEvent<Server> event) {
        // Setup the data directory here.
        this.resetDataPath();
        final IStorageManager manager = this.serviceCollection.storageManager();

        // in case.
        manager.saveAndInvalidateAllCaches().whenComplete((v, t) -> manager.detachAll()).join();
        manager.detachAll();
        this.dataDirectory = null;

        manager.attachAll();
        final IReloadableService reloadableService = this.serviceCollection.reloadableService();
        reloadableService.fireDataFileReloadables(this.serviceCollection);
        reloadableService.fireReloadables(this.serviceCollection);
    }

    @Listener
    public void serverStarted(final StartedEngineEvent<Server> event) {
        if (this.runDocGen) {
            final Path finalPath;
            try {
                final String docgenPath = System.getProperty(DOCGEN_PROPERTY);
                if (docgenPath.isEmpty()) {
                    finalPath = this.getDataDirectory();
                } else {
                    final Path path = Sponge.getGame().getGameDirectory().resolve(docgenPath);
                    boolean isOk = path.toAbsolutePath().startsWith(Sponge.getGame().getGameDirectory().toAbsolutePath());
                    isOk &= Files.notExists(path) || Files.isDirectory(path);
                    if (isOk) {
                        Files.createDirectories(path);
                        finalPath = path;
                    } else {
                        finalPath = this.getDataDirectory();
                    }
                }
                this.logger.info("Starting generation of documentation, saving files to: {}", finalPath.toString());
                this.serviceCollection.documentationGenerationService().generate(finalPath);
                this.logger.info("Generation is complete. Server will shut down.");
            } catch (final Exception ex) {
                this.logger.error("Could not generate. Server will shut down.");
                ex.printStackTrace();
            }

            Sponge.getServer().shutdown();
            return;
        }
        this.onStartedActions.forEach(Action::action);
        this.serviceCollection.getServiceUnchecked(UniqueUserService.class).resetUniqueUserCount();
        event.getGame().getAsyncScheduler().createExecutor(this.pluginContainer)
                .submit(() -> this.serviceCollection.userCacheService().startFilewalkIfNeeded());
        this.serviceCollection.platformService().setGameStartedTime();
    }

    @Listener
    public void serverStopping(final StoppingEngineEvent<Server> event) {
        // Teardown data here
        final IStorageManager manager = this.serviceCollection.storageManager();
        manager.saveAndInvalidateAllCaches().whenComplete((v, t) -> manager.detachAll());
        Sponge.getAsyncScheduler().getTasksByPlugin(this.pluginContainer).forEach(ScheduledTask::cancel);
    }

    @Listener
    public void reloadConfig(final RefreshGameEvent event) {
        this.serviceCollection.configProvider().reload();
        this.serviceCollection.reloadableService().fireReloadables(this.serviceCollection);
    }

    // -- Module loading

    private LinkedList<Tuple<ModuleContainer, IModule>> startModuleLoading() {
        final Collection<ModuleContainer> moduleContainerCollection = this.provider.getModules();
        final LinkedList<Tuple<ModuleContainer, IModule>> modules = new LinkedList<>();
        for (final ModuleContainer container : this.filterModules(moduleContainerCollection)) {
            final IModule module;
            try {
                module = container.construct(this.injector);
            } catch (final RuntimeException e) {
                this.logger.error("Could not load module {}. Skipping...", container.getId(), e);
                continue;
            }

            module.init(this.serviceCollection);
            if (module instanceof IModule.Configurable) {
                this.registerConfigurableModule(container, (IModule.Configurable<?>) module);
            }

            modules.add(Tuple.of(container, module));
        }

        return modules;
    }

    private <T> void registerConfigurableModule(final ModuleContainer container, final IModule.Configurable<T> configurable) {
        this.serviceCollection.configurateHelper().addTypeSerialiser(configurable.moduleTypeSerializers());
        this.serviceCollection.configProvider().registerModuleConfig(
                container.getId(),
                configurable.getConfigClass(),
                configurable::createInstance,
                configurable.getTransformations());
    }

    private void completeModuleInit(final Collection<Tuple<ModuleContainer, IModule>> modules) {
        for (final Tuple<ModuleContainer, IModule> tuple : modules) {
            final IModule module = tuple.getSecond();
            final ModuleContainer container = tuple.getFirst();
            // listeners
            Sponge.getEventManager().registerListeners(this.pluginContainer, module);
            for (final Class<? extends ListenerBase> listenerClass : module.getListeners()) {
                final ListenerBase listener = this.injector.getInstance(listenerClass);
                if (listener instanceof ListenerBase.Conditional) {
                    this.serviceCollection.reloadableService().registerReloadable(new ListenerReloadableWrapper((ListenerBase.Conditional) listener));
                } else {
                    Sponge.getEventManager().registerListeners(this.pluginContainer, listener);
                }
            }

            // commands
            this.serviceCollection.commandMetadataService().registerCommands(container.getId(), container.getName(), module.getCommands());
            this.serviceCollection.commandMetadataService().registerInterceptors(module.getCommandInterceptors());

            // tasks
            for (final Class<? extends TaskBase> taskBaseClass : module.getAsyncTasks()) {
                final TaskBase taskBase = this.injector.getInstance(taskBaseClass);
                if (taskBase instanceof IReloadableService.Reloadable) {
                    this.serviceCollection.reloadableService().registerReloadable((IReloadableService.Reloadable) taskBase);
                }
                this.onStartedActions.add(() -> Sponge.getAsyncScheduler()
                        .createExecutor(this.pluginContainer)
                        .scheduleAtFixedRate(
                                taskBase,
                                taskBase.interval().getSeconds(),
                                taskBase.interval().getSeconds(),
                                TimeUnit.SECONDS));
            }

            for (final Class<? extends SyncTaskBase> taskBaseClass : module.getSyncTasks()) {
                final SyncTaskBase taskBase = this.injector.getInstance(taskBaseClass);
                if (taskBase instanceof IReloadableService.Reloadable) {
                    this.serviceCollection.reloadableService().registerReloadable((IReloadableService.Reloadable) taskBase);
                }
                this.onStartedActions.add(() -> Sponge.getServer().getScheduler()
                        .submit(
                                Task.builder().plugin(this.pluginContainer)
                                        .delay(taskBase.interval().getSeconds(), TimeUnit.SECONDS)
                                        .interval(taskBase.interval().getSeconds(), TimeUnit.SECONDS)
                                        .execute(taskBase)
                                        .build()
                        ));
            }

            // Player info service
            module.getInfoProvider().ifPresent(x -> this.serviceCollection.playerInformationService().registerProvider(x));

            // Register permissions in the description builder
            module.getPermissions().ifPresent(x -> this.serviceCollection.permissionService().register(container.getId(), x));
        }
    }

    private Collection<ModuleContainer> filterModules(final Collection<ModuleContainer> moduleContainers) {
        final CommentedConfigurationNode defaults = this.serviceCollection.configurateHelper().createNode();
        for (final ModuleContainer moduleContainer : moduleContainers) {
            try {
                defaults.node(moduleContainer.getId()).set(ModuleState.TRUE);
            } catch (final SerializationException e) {
                // ignored
            }
        }
        final Collection<String> modules = moduleContainers.stream().map(ModuleContainer::getId).collect(Collectors.toList());
        modules.add("core");
        this.serviceCollection.moduleReporter().provideDiscoveredModules(modules);

        final ConfigurationLoader<CommentedConfigurationNode> moduleConfig = HoconConfigurationLoader.builder()
                .path(this.configDirectory.resolve("modules.conf"))
                .build();

        CommentedConfigurationNode node;
        try {
            node = moduleConfig.load(this.serviceCollection.configurateHelper().getOptions());
            node.mergeFrom(defaults);
            moduleConfig.save(node);
        } catch (final ConfigurateException e) {
            node = defaults;
            this.logger.error("Could not load module config. Defaulting all to TRUE.", e);
        }

        final CommentedConfigurationNode finalNode = node;
        final ModuleEvent event = new ModuleEvent(
                Cause.of(EventContext.empty(), this.pluginContainer),
                moduleContainers.stream().map(ModuleContainer::getId).collect(Collectors.toSet()),
                moduleContainers.stream().map(ModuleContainer::getId)
                        .filter(x -> {
                            try {
                                return finalNode.node(x).get(TypeToken.get(ModuleState.class), ModuleState.TRUE) == ModuleState.TRUE;
                            } catch (final SerializationException e) {
                                return true;
                            }
                        }).collect(Collectors.toSet()));
        Sponge.getEventManager().post(event);
        final ArrayList<ModuleContainer> containersToReturn = new ArrayList<>();
        containersToReturn.add(new ModuleContainer("core", "Core", true, CoreModule.class));
        for (final ModuleContainer moduleContainer : moduleContainers) {
            if (event.shouldLoad(moduleContainer.getId())) {
                containersToReturn.add(moduleContainer);
                this.serviceCollection.moduleReporter().provideEnabledModule(moduleContainer);
            }
        }
        return containersToReturn;
    }

    private void resetDataPath() {
        final CoreConfig config = this.serviceCollection.configProvider().getCoreConfig();
        final String dataFileLocation = config.getDataFileLocation();
        if (dataFileLocation == null || dataFileLocation.equalsIgnoreCase("default")) {
            this.dataDirectory = null; // this will be set later.
            return;
        }

        final Path path;
        try {
            path = Paths.get(dataFileLocation);
        } catch (final InvalidPathException ex) {
            // Tell the user we're going default.
            this.dataDirectory = null;
            this.logger.error("The data path '" + dataFileLocation + "' is not a valid path. Using default data directory.");
            return;
        }

        if (!Files.isDirectory(path)) {
            // warning
            this.logger.error(this.serviceCollection.messageProvider().getMessageString("nucleus.custompath.error",
                    path.toAbsolutePath().toString()));
            this.dataDirectory = null;
            return;
        }

        final Path currentDataDir = path.resolve("nucleus");
        try {
            Files.createDirectories(currentDataDir);
            this.dataDirectory = currentDataDir;
            this.logger.info(this.serviceCollection.messageProvider().getMessageString("nucleus.custompath.info",
                    currentDataDir.toAbsolutePath().toString()));
        } catch (final IOException e) {
            this.logger.fatal("Could not create directory '{}', using default data directory.", currentDataDir.toAbsolutePath(), e);
        }

    }

    private Collection<ConfigurationTransformation> coreConfigurationTransformations() {
        return Collections.emptyList();
    }

    public enum ModuleState {

        FORCE(true),
        TRUE(true),
        FALSE(false);

        private final boolean shouldLoad;

        ModuleState(final boolean shouldLoad) {
            this.shouldLoad = shouldLoad;
        }

        public boolean isShouldLoad() {
            return this.shouldLoad;
        }
    }

}
