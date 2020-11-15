/*
 * This file is part of Nucleus, licensed under the MIT License (MIT). See the LICENSE.txt file
 * at the root of this project for more details.
 */
package io.github.nucleuspowered.nucleus.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.TypeLiteral;
import io.github.nucleuspowered.nucleus.IPluginInfo;
import io.github.nucleuspowered.nucleus.NucleusCore;
import io.github.nucleuspowered.nucleus.services.INucleusServiceCollection;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The base module that Nucleus will use to construct its basic services.
 */
public class NucleusInjectorModule extends AbstractModule {

    private final Supplier<NucleusCore> coreSupplier;
    private final IPluginInfo pluginInfo;

    public NucleusInjectorModule(
            final Supplier<NucleusCore> core,
            final IPluginInfo pluginInfo
    ) {
        this.coreSupplier = core;
        this.pluginInfo = pluginInfo;
    }

    @Override
    protected void configure() {
        this.bind(new TypeLiteral<Supplier<Path>>() {}).annotatedWith(DataDirectory.class).toInstance(this.coreSupplier.get()::getDataDirectory);
        this.bind(Path.class).annotatedWith(ConfigDirectory.class).toInstance(this.coreSupplier.get().getConfigDirectory());
        this.bind(IPluginInfo.class).toInstance(this.pluginInfo);
    }

    @Provides
    private INucleusServiceCollection provideServiceCollection() {
        return this.coreSupplier.get().getServiceCollection();
    }

}
