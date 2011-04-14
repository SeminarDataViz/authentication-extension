/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.Configuration;
import org.neo4j.server.configuration.Configurator;
import org.neo4j.server.configuration.PropertyFileConfigurator;
import org.neo4j.server.configuration.validation.DatabaseLocationMustBeSpecifiedRule;
import org.neo4j.server.configuration.validation.Validator;
import org.neo4j.server.database.Database;
import org.neo4j.server.database.GraphDatabaseFactory;
import org.neo4j.server.logging.Logger;
import org.neo4j.server.modules.PluginInitializer;
import org.neo4j.server.modules.RESTApiModule;
import org.neo4j.server.modules.ServerModule;
import org.neo4j.server.plugins.Injectable;
import org.neo4j.server.plugins.PluginManager;
import org.neo4j.server.startup.healthcheck.StartupHealthCheck;
import org.neo4j.server.startup.healthcheck.StartupHealthCheckFailedException;
import org.neo4j.server.web.WebServer;

public class NeoServerWithEmbeddedWebServer implements NeoServer {

    public static final Logger log = Logger.getLogger(NeoServerWithEmbeddedWebServer.class);

    private final File configFile;
    private Configurator configurator;
    private Database database;
    private final WebServer webServer;
    private final StartupHealthCheck startupHealthCheck;

    private final AddressResolver addressResolver;

    private final List<ServerModule> serverModules = new ArrayList<ServerModule>();
    private PluginInitializer pluginInitializer;
    // Perhaps this shouldn't reference (an instance of) the Bootstrapper class,
    // but we need to provide configuration parameters to load the appropriate
    // GraphDatabaseFactory implementation
    private final Bootstrapper bootstrapper;

    public NeoServerWithEmbeddedWebServer( Bootstrapper bootstrapper, AddressResolver addressResolver,
            StartupHealthCheck startupHealthCheck, File configFile, WebServer webServer,
            Iterable<Class<? extends ServerModule>> moduleClasses )
    {
        this.bootstrapper = bootstrapper;
        this.addressResolver = addressResolver;
        this.startupHealthCheck = startupHealthCheck;
        this.configFile = configFile;
        this.webServer = webServer;
        webServer.setNeoServer( this );
        for ( Class<? extends ServerModule> moduleClass : moduleClasses )
        {
            registerModule( moduleClass );
        }

        this.configurator = new PropertyFileConfigurator(new Validator(new DatabaseLocationMustBeSpecifiedRule()), configFile);
    }

    public NeoServerWithEmbeddedWebServer( Bootstrapper bootstrapper, StartupHealthCheck startupHealthCheck,
            File configFile, WebServer ws, Iterable<Class<? extends ServerModule>> mc )
    {
        this( bootstrapper, new AddressResolver(), startupHealthCheck, configFile, ws, mc );
    }

    @Override
    public void start() {
        // Start at the bottom of the stack and work upwards to the Web container
        startupHealthCheck();

        startDatabase();

        startExtensionInitialization();

        startModules();

        startWebServer();
    }

    /**
     *  Initializes individual plugins using the mechanism provided via @{see PluginInitializer} and the java service locator
     */
    protected void startExtensionInitialization()
    {
        pluginInitializer = new PluginInitializer( this );
    }

    /**
     * Use this method to register server modules from subclasses
     * @param clazz
     */
    protected final void registerModule(Class<? extends ServerModule> clazz) {
        try {
            serverModules.add(clazz.newInstance());
        } catch (Exception e) {
            log.warn( "Failed to instantiate server module [%s], reason: %s", clazz.getName(), e.getMessage() );
        }
    }

    private void startModules() {
        for(ServerModule module : serverModules) {
            module.start(this);
        }
    }

    private void stopModules() {
        for(ServerModule module : serverModules) {

            try{
                module.stop();
            }
            catch(Exception e)
            {
               log.error( e );
            }
        }
    }

    private void startupHealthCheck() {

    }

    private void startDatabase() {
        String dbLocation = new File(configurator.configuration().getString(Configurator.DATABASE_LOCATION_PROPERTY_KEY)).getAbsolutePath();
        GraphDatabaseFactory dbFactory = bootstrapper.getGraphDatabaseFactory( configurator.configuration() );
        Map<String, String> databaseTuningProperties = configurator.getDatabaseTuningProperties();
        if (databaseTuningProperties != null) {
            this.database = new Database( dbFactory, dbLocation, databaseTuningProperties );
        } else {
            this.database = new Database( dbFactory, dbLocation );
        }
    }

    @Override
    public Configuration getConfiguration() {
        return configurator.configuration();
    }

    private void startWebServer() {

        int webServerPort = getWebServerPort();

        log.info("Starting Neo Server on port [%s]", webServerPort);
        webServer.setPort(webServerPort);


        try {
            webServer.start();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Failed to start Neo Server on port [%d], reason [%s]", getWebServerPort(), e.getMessage());
        }
    }

    protected int getWebServerPort() {
        return configurator.configuration().getInt(Configurator.WEBSERVER_PORT_PROPERTY_KEY, Configurator.DEFAULT_WEBSERVER_PORT);
    }

    @Override
    public void stop() {
        try {
            stopModules();
            stopExtensionInitializers();
            stopDatabase();
            stopWebServer();
            log.info("Successfully shutdown Neo Server on port [%d], database [%s]", getWebServerPort(), getDatabase().getLocation());
        } catch (Exception e) {
            log.warn("Failed to cleanly shutdown Neo Server on port [%d], database [%s]. Reason: %s", getWebServerPort(), getDatabase().getLocation(),
                    e.getMessage());
        }
    }

    /**
     * shuts down initializers of individual plugins
     */
    private void stopExtensionInitializers()
    {
        pluginInitializer.stop(  );
    }

    private void stopWebServer() {
        if (webServer != null) {
            webServer.stop();
        }
    }

    private void stopDatabase() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Override
    public Database getDatabase() {
        return database;
    }


    public URI baseUri() {
        StringBuilder sb = new StringBuilder();
        sb.append("http");
        int webServerPort = getWebServerPort();
        if (webServerPort == 443) {
            sb.append("s");

        }
        sb.append("://");
        sb.append(addressResolver.getHostname());

        if (webServerPort != 80) {
            sb.append(":");
            sb.append(webServerPort);
        }
        sb.append("/");

        try {
            return new URI(sb.toString());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public WebServer getWebServer() {
        return webServer;
    }

    @Override
    public Configurator getConfigurator() {
        return configurator;
    }

    @Override
    public PluginManager getExtensionManager() {
        if(hasModule(RESTApiModule.class)) {
            return getModule(RESTApiModule.class).getPlugins();
        } else {
            return null;
        }
    }

    @Override
    public Collection<Injectable<?>> getInjectables( List<String> packageNames )
    {
        return pluginInitializer.intitializePackages( packageNames );
    }

    private boolean hasModule(Class<? extends ServerModule> clazz) {
        for(ServerModule sm : serverModules) {
            if(sm.getClass() == clazz) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private <T extends ServerModule> T getModule(Class<T> clazz) {
        for(ServerModule sm : serverModules) {
            if(sm.getClass() == clazz) {
                return (T) sm;
            }
        }

        return null;
    }

}