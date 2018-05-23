/*******************************************************************************
 * Copyright (c) 2012,2016 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.mongo.internal;

import java.beans.IntrospectionException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.KeyStoreException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;

import com.ibm.websphere.crypto.PasswordUtil;
import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.websphere.ras.annotation.Trivial;
import com.ibm.ws.ffdc.FFDCFilter;
import com.ibm.ws.ffdc.annotation.FFDCIgnore;
import com.ibm.ws.mongo.MongoChangeListener;
import com.ibm.ws.mongo.MongoSslHelper;
import com.ibm.wsspi.kernel.service.utils.AtomicServiceReference;
import com.ibm.wsspi.kernel.service.utils.OnErrorUtil;
import com.ibm.wsspi.kernel.service.utils.OnErrorUtil.OnError;
import com.ibm.wsspi.kernel.service.utils.SerializableProtectedString;
import com.ibm.wsspi.library.Library;
import com.ibm.wsspi.library.LibraryChangeListener;

/**
 * A declarative services component can be completely POJO based
 * (no awareness/use of OSGi services).
 *
 * OSGi methods (activate/deactivate) should be protected.
 */
public class MongoService implements LibraryChangeListener, MongoChangeListener {
    private static final TraceComponent tc = Tr.register(MongoService.class);

    private static int MIN_MAJOR = 2;
    private static int MIN_MINOR = 10;
    private static int SSL_MIN_MINOR = 11;
    private static int CERT_AUTH_MIN_MINOR = 12;

    private static final String MONGO_CLIENT_CLS_STR = "com.mongodb.MongoClient";
    private static final String MONGO_CLIENT_OPTIONS_CLS_STR = "com.mongodb.MongoClientOptions";
    private static final String MONGO_CLIENT_OPTIONS_BUILDER_CLS_STR = "com.mongodb.MongoClientOptions$Builder";
    private static final String MONGO_CLIENT_URI_CLS_STR = "com.mongodb.MongoClientURI";
    private static final String MONGO_CREDENTIAL_CLS = "com.mongodb.MongoCredential";
    private static final String MONGO_READPREFERENCE_CLS_STR = "com.mongodb.ReadPreference";
    private static final String MONGO_WRITECONCERN_CLS_STR = "com.mongodb.WriteConcern";
    private static final String READ_PREFERENCE_METHOD_NAME = "readPreference";
    private static final String WRITE_CONCERN_METHOD_NAME = "writeConcern";
    private static final String SOCKET_FACTORY_METHOD_NAME = "socketFactory";
    private static final String MONGO_MAJOR_VERSION_METHOD_NAME = "getMajorVersion";
    private static final String MONGO_MINOR_VERSION_METHOD_NAME = "getMinorVersion";

    // Version 1.0 of the monogo driver used these static variables, but were deprecated in a subsequent release.
    // If getMajor/MinorVersion doesn't exist, will check for these fields.
    private static final String MONGO_MAJOR_VERSION_STATIC_VAR_NAME = "MAJOR_VERSION";
    private static final String MONGO_MINOR_VERSION_STATIC_VAR_NAME = "MINOR_VERSION";
    private static final String MONGO_CLS_STR = "com.mongodb.Mongo";

    /**  */
    private static final String LIBRARY_KEY = "library";

    /**
     * Reference to the shared library that contains the MongoDB Java driver.
     */
    private final AtomicServiceReference<Library> libraryRef = new AtomicServiceReference<Library>(LIBRARY_KEY);

    private static final String SSL_KEY = "ssl";
    /**
     * Reference to the SSL configuration (if any)
     */
    private final AtomicServiceReference<Object> sslConfigurationRef = new AtomicServiceReference<Object>(SSL_KEY);

    /**
     * Reference to the KeyStoreService
     */
    private final AtomicServiceReference<Object> keyStoreServiceRef = new AtomicServiceReference<Object>(KEY_KEYSTORE_SERVICE_REF);
    private static final String KEY_KEYSTORE_SERVICE_REF = "keyStoreService";

    /**
     * Name of the unique identifier property
     */
    static final String CONFIG_ID = "config.displayId";

    /**
     * Name of property that specifies the list of host names.
     */
    private static final String HOST_NAMES = "hostNames";

    /**
     * Config element alias: mongo.
     */
    static final String MONGO = "mongo";

    /**
     * Name of the password property.
     */
    private static final String PASSWORD = "password";

    /**
     * Name of property that specifies the list of port numbers.
     */
    private static final String PORTS = "ports";

    /**
     * Special case property in MongoOptions due to the data type.
     */
    private static final String READ_PREFERENCE = "readPreference";

    private static final String WRITE_CONCERN = "writeConcern";

    /**
     * Name of the user property.
     */
    private static final String USER = "user";

    /**
     * Name of the SSL enabled property.
     */
    private static final String SSL_ENABLED = "sslEnabled";

    /**
     * Name of the SSL ref property.
     */
    private static final String SSL_REF = "sslRef";

    /**
     * Subject of the certificate for certificate authentication.
     */
    private static final String USE_CERTIFICATE_AUTHENTICATION = "useCertificateAuthentication";

    /**
     * Names of properties that we should avoid attempting to configure directly as MongoOptions.
     */
    private static final Set<String> NOT_MONGO_CLIENT_OPTIONS = new HashSet<String>(Arrays.asList(HOST_NAMES,
                                                                                                  "id",
                                                                                                  "libraryRef",
                                                                                                  Constants.OBJECTCLASS,
                                                                                                  OnErrorUtil.CFG_KEY_ON_ERROR,
                                                                                                  PASSWORD,
                                                                                                  PORTS,
                                                                                                  READ_PREFERENCE,
                                                                                                  WRITE_CONCERN,
                                                                                                  USER,
                                                                                                  SSL_ENABLED,
                                                                                                  SSL_REF,
                                                                                                  USE_CERTIFICATE_AUTHENTICATION));
    /**
     * Mapping of property name to value value type. This is used to find the correct 'setter' method on
     * MongoClientOpetions.Builder.
     */
    private static final Map<String, Class<?>> MONGO_CLIENT_OPTIONS_TYPES;

    static {
        MONGO_CLIENT_OPTIONS_TYPES = new HashMap<String, Class<?>>();
        MONGO_CLIENT_OPTIONS_TYPES.put("autoConnectRetry", boolean.class);
        MONGO_CLIENT_OPTIONS_TYPES.put("connectionsPerHost", int.class);
        MONGO_CLIENT_OPTIONS_TYPES.put("connectTimeout", int.class);
        MONGO_CLIENT_OPTIONS_TYPES.put("cursorFinalizerEnabled", boolean.class);

        MONGO_CLIENT_OPTIONS_TYPES.put("description", String.class);
        MONGO_CLIENT_OPTIONS_TYPES.put("maxAutoConnectRetryTime", long.class);
        MONGO_CLIENT_OPTIONS_TYPES.put("maxWaitTime", int.class);

        MONGO_CLIENT_OPTIONS_TYPES.put("socketKeepAlive", boolean.class);
        MONGO_CLIENT_OPTIONS_TYPES.put("socketTimeout", int.class);
        MONGO_CLIENT_OPTIONS_TYPES.put("threadsAllowedToBlockForConnectionMultiplier", int.class);
    }

    /**
     * Method that authenticates with the DB instance.
     */
    private Method DB_authenticate;

    /**
     * Method that tells us if we have already authenticated.
     */
    private Method DB_isAuthenticated;

    /**
     * Unique identifier for this mongo instance.
     */
    private String id;

    /**
     * Lock for lazy initialization.
     * Don't access any fields (except for id and libraryRef) unless you have the lock.
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * com.mongodb.MongoClient instance
     */
    private Object mongo;

    /**
     * Method that creates DB instances.
     */
    private Method Mongo_getDB;

    /**
     * Config properties.
     */
    private Map<String, Object> props;

    /**
     * Are we using certificate based authentication to log on to MongoDB.
     */
    private boolean useCertAuth = false;

    /**
     * Reference to the SSL Helper class
     * <p>
     * If SSL is not enabled, this will be null
     */
    private MongoSslHelper sslHelper;

    private final Set<MongoChangeListener> changeListeners = Collections.synchronizedSet(new HashSet<MongoChangeListener>());

    /**
     * DS method to activate this component.
     * Best practice: this should be a protected method, not public or private
     *
     * @param context DeclarativeService defined/populated component context
     * @param props DeclarativeService defined/populated map of service properties
     */
    protected void activate(ComponentContext context, Map<String, Object> props) {
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, "MongoService activate");
        }
        libraryRef.activate(context);
        sslConfigurationRef.activate(context);
        this.props = props;
        keyStoreServiceRef.activate(context);
        id = (String) props.get(CONFIG_ID);
    }

    /**
     * DS method to deactivate this component.
     * Best practice: this should be a protected method, not public or private
     *
     * @param context DeclarativeService defined/populated component context
     * @throws Exception if unable to close the Mongo instance
     */
    protected void deactivate(ComponentContext context) throws Exception {
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, "MongoService deactivate");
        }

        try {
            closeMongoConnection();
            if (sslHelper != null) {
                sslHelper.removeChangeListener(this);
            }
        } finally {
            libraryRef.deactivate(context);
            sslConfigurationRef.deactivate(context);
            keyStoreServiceRef.deactivate(context);
        }
    }

    /**
     * Get a Mongo DB instance, authenticated with the specified user and password if specified.
     *
     * @param databaseName the database name.
     * @return com.mongodb.DB instance
     * @throws Exception if an error occurs.
     */
    @FFDCIgnore(InvocationTargetException.class)
    Object getDB(String databaseName) throws Exception {
        final boolean trace = TraceComponent.isAnyTracingEnabled();

        lock.readLock().lock();
        try {
            if (mongo == null) {
                // Switch to write lock for lazy initialization
                lock.readLock().unlock();
                lock.writeLock().lock();
                try {
                    if (mongo == null)
                        init();
                } finally {
                    // Downgrade to read lock for rest of method
                    lock.readLock().lock();
                    lock.writeLock().unlock();
                }
            }

            Object db = Mongo_getDB.invoke(mongo, databaseName);

            // authentication
            String user = (String) props.get(USER);
            if (user != null) {
                if ((Boolean) DB_isAuthenticated.invoke(db)) {
                    if (trace && tc.isDebugEnabled())
                        Tr.debug(this, tc, "already authenticated");
                } else {
                    if (trace && tc.isDebugEnabled())
                        Tr.debug(this, tc, "authenticate as: " + user);
                    SerializableProtectedString password = (SerializableProtectedString) props.get(PASSWORD);
                    String pwdStr = password == null ? null : String.valueOf(password.getChars());
                    pwdStr = PasswordUtil.getCryptoAlgorithm(pwdStr) == null ? pwdStr : PasswordUtil.decode(pwdStr);
                    char[] pwdChars = pwdStr == null ? null : pwdStr.toCharArray();
                    try {
                        if (!(Boolean) DB_authenticate.invoke(db, user, pwdChars))
                            if ((Boolean) DB_isAuthenticated.invoke(db)) {
                                if (trace && tc.isDebugEnabled())
                                    Tr.debug(this, tc, "another thread must have authenticated first");
                            } else
                                throw new IllegalArgumentException(Utils.getMessage("CWKKD0012.authentication.error", MONGO, id, databaseName));
                    } catch (InvocationTargetException x) {
                        // If already authenticated, Mongo raises:
                        // IllegalStateException: can't authenticate twice on the same database
                        // Maybe another thread did the authentication right after we checked, so check again.
                        Throwable cause = x.getCause();
                        if (cause instanceof IllegalStateException && (Boolean) DB_isAuthenticated.invoke(db)) {
                            if (trace && tc.isDebugEnabled())
                                Tr.debug(this, tc, "another thread must have authenticated first", cause);
                        } else
                            throw cause;
                    }
                }
            } else if (useCertAuth) {
                // If we specified a certificate we will already have used the client constructor that
                // specified the credential so if we have got to here we are already authenticated and
                // JIT should remove this so it will not be an overhead.
            }
            return db;
        } catch (Throwable x) {
            // rethrowing the exception allows it to be captured in FFDC and traced automatically
            x = x instanceof InvocationTargetException ? x.getCause() : x;
            if (x instanceof Exception)
                throw (Exception) x;
            else if (x instanceof Error)
                throw (Error) x;
            else
                throw new RuntimeException(x);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Ignore, warn, or fail when a configuration error occurs.
     * This is copied from Tim's code in tWAS and updated slightly to
     * override with the Liberty ignore/warn/fail setting.
     * Precondition: invoker must have lock on this MongoDBService instance, in order to read the onError property.
     *
     * @param throwable an already created Throwable object, which can be used if the desired action is fail.
     * @param exceptionClassToRaise the class of the Throwable object to return
     * @param msgKey the NLS message key
     * @param objs list of objects to substitute in the NLS message
     * @return either null or the Throwable object
     */
    private <T extends Throwable> T ignoreWarnOrFail(Throwable throwable, Class<T> exceptionClassToRaise, String msgKey, Object... objs) {

        // Read the value each time in order to allow for changes to the onError setting
        switch ((OnError) props.get(OnErrorUtil.CFG_KEY_ON_ERROR)) {
            case IGNORE:
                if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled())
                    Tr.debug(this, tc, "ignoring error: " + msgKey, objs);
                return null;
            case WARN:
                Tr.warning(tc, msgKey, objs);
                return null;
            case FAIL:
                try {
                    if (throwable != null && exceptionClassToRaise.isInstance(throwable))
                        return exceptionClassToRaise.cast(throwable);

                    Constructor<T> con = exceptionClassToRaise.getConstructor(String.class);
                    String message = msgKey == null ? throwable.getMessage() : Utils.getMessage(msgKey, objs);
                    T failure = con.newInstance(message);
                    failure.initCause(throwable);
                    return failure;
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
        }

        return null;
    }

    /**
     * Lazy initialization.
     * Precondition: invoker must have write lock on this MongoDBService instance
     *
     * @throws Exception if unable to initialize
     */
    private void init() throws Exception {

        //Initialise URI. Only used when constructing mongo with the default SSL turned on.
        String uri = "mongodb://";

        boolean sslEnabled = (((Boolean) props.get(SSL_ENABLED)) == null) ? false : (Boolean) props.get(SSL_ENABLED);
        this.useCertAuth = (((Boolean) props.get(USE_CERTIFICATE_AUTHENTICATION)) == null) ? false : (Boolean) props.get(USE_CERTIFICATE_AUTHENTICATION);

        ClassLoader loader = libraryRef.getServiceWithException().getClassLoader();
        assertLibrary(loader, sslEnabled);
        assertValidSSLConfig();

        // Initialize List<ServerAddress>
        List<Object> serverAddresses = new LinkedList<Object>();
        Constructor<?> constructor = loader.loadClass("com.mongodb.ServerAddress").getConstructor(String.class, int.class);
        String[] hosts = (String[]) props.get(HOST_NAMES);
        int[] ports = (int[]) props.get(PORTS);
        int numServerAddresses = hosts.length;
        if (hosts.length != ports.length) {
            IllegalArgumentException failure = ignoreWarnOrFail(null, IllegalArgumentException.class, "CWKKD0011.hosts.ports.mismatch",
                                                                MONGO, id, hosts.length, ports.length);
            if (failure == null) // tolerate the error and keep going
                numServerAddresses = hosts.length < ports.length ? hosts.length : ports.length;
            else
                throw failure;
        }

        StringBuilder uriBuilder = new StringBuilder();
        uriBuilder.append(uri);
        for (int i = 0; i < numServerAddresses; i++) {
            serverAddresses.add(constructor.newInstance(hosts[i], ports[i]));
            //Add server addresses to URI
            uriBuilder.append(hosts[i] + ":" + ports[i]);
            if (i != numServerAddresses - 1) {
                uriBuilder.append(",");
            }
        }

        // Initialize MongoOptions
        Class<?> mongoClientOptionsCls = loader.loadClass(MONGO_CLIENT_OPTIONS_CLS_STR);
        Class<?> mongoClientOptionsBuilderCls = loader.loadClass(MONGO_CLIENT_OPTIONS_BUILDER_CLS_STR);

        Object builderInstance = mongoClientOptionsBuilderCls.newInstance();
        for (Map.Entry<String, Object> prop : props.entrySet()) {
            String name = prop.getKey();
            Object value = prop.getValue();
            if (value != null && name.indexOf('.') < 0 && !NOT_MONGO_CLIENT_OPTIONS.contains(name))
                set(mongoClientOptionsBuilderCls, builderInstance, name, value);
        }

        String value = (String) props.get(READ_PREFERENCE);
        if (value != null) {
            setReadPreference(mongoClientOptionsBuilderCls, builderInstance, value);
        }
        value = (String) props.get(WRITE_CONCERN);
        if (value != null) {
            setWriteConcern(mongoClientOptionsBuilderCls, builderInstance, value);
        }

        //SSL setup
        boolean sslRefExists = props.containsKey(SSL_REF);
        Properties sslProperties = null;
        if (sslEnabled) {
            Map<String, Object> connectionInfo = getConnectionInfo();
            setSocketFactory(mongoClientOptionsBuilderCls, builderInstance, connectionInfo);
            sslProperties = sslHelper.getSSLProperties(sslConfigurationRef.getService(), connectionInfo, this);
        }
        // Initialize the Mongo instance
        Class<?> mongoClientCls = loader.loadClass(MONGO_CLIENT_CLS_STR);
        Class<?> mongoClientUriCls = loader.loadClass(MONGO_CLIENT_URI_CLS_STR);
        if (sslEnabled && !sslRefExists && !useCertAuth) {
            // TODO check if this is still needed as we are setting the socket factory above
            uriBuilder.append("/?ssl=true");
            uri = uriBuilder.toString();
            constructor = mongoClientUriCls.getConstructor(String.class, mongoClientOptionsBuilderCls);
            Object uriObject = constructor.newInstance(uri, builderInstance);
            constructor = mongoClientCls.getConstructor(mongoClientUriCls);
            mongo = constructor.newInstance(uriObject);

        } else if (sslEnabled && useCertAuth) {
            // get the name from the certificate and handle exceptions / null
            String certificateDN = getCerticateSubject(keyStoreServiceRef, sslProperties);

            // Create an x509 credential calling the MongoCredential class reflectively passing the DN
            Class<?> mongoCredentialCls = loader.loadClass(MONGO_CREDENTIAL_CLS);
            Method createCertificateMethod = mongoCredentialCls.getMethod("createMongoX509Credential", String.class);
            Object credential = createCertificateMethod.invoke(null, certificateDN);

            // create a client authenticated using this credential
            constructor = mongoClientCls.getConstructor(List.class, List.class, mongoClientOptionsCls);
            Method builderBuildMethod = mongoClientOptionsBuilderCls.getMethod("build");
            mongo = constructor.newInstance(serverAddresses, Arrays.asList(credential), builderBuildMethod.invoke(builderInstance));

        } else {
            constructor = mongoClientCls.getConstructor(List.class, mongoClientOptionsCls);
            // need to call MongoClientOptions.Builder.build();
            Method builderBuildMethod = mongoClientOptionsBuilderCls.getMethod("build");
            mongo = constructor.newInstance(serverAddresses, builderBuildMethod.invoke(builderInstance));
        }

        // Methods that we need each each time a DB instance is created
        Mongo_getDB = mongoClientCls.getMethod("getDB", String.class);
        Class<?> DB = loader.loadClass("com.mongodb.DB");
        DB_authenticate = DB.getMethod("authenticate", String.class, char[].class);
        DB_isAuthenticated = DB.getMethod("isAuthenticated");
    }

    /**
     * Call security code to read the subject name from the key in the keystore
     *
     * @param serviceRef
     * @param sslProperties
     * @return
     */
    private String getCerticateSubject(AtomicServiceReference<Object> serviceRef, Properties sslProperties) {
        String certificateDN = null;
        try {
            certificateDN = sslHelper.getClientKeyCertSubject(serviceRef, sslProperties);
        } catch (KeyStoreException ke) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.error(tc, "CWKKD0020.ssl.get.certificate.user", MONGO, id, ke);
            }
            throw new RuntimeException(Utils.getMessage("CWKKD0020.ssl.get.certificate.user", MONGO, id, ke));
        } catch (CertificateException ce) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.error(tc, "CWKKD0020.ssl.get.certificate.user", MONGO, id, ce);
            }
            throw new RuntimeException(Utils.getMessage("CWKKD0020.ssl.get.certificate.user", MONGO, id, ce));
        }

        // handle null .... cannot find the client key
        if (certificateDN == null) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.error(tc, "CWKKD0026.ssl.certificate.exception", MONGO, id);
            }
            throw new RuntimeException(Utils.getMessage("CWKKD0026.ssl.certificate.exception", MONGO, id));
        }

        return certificateDN;
    }

    /**
     * Gets the Map of connection information (direction, host, port) from the SSLHelper class
     *
     * @return connection information Map
     */
    private Map<String, Object> getConnectionInfo() {
        String[] hostnames = (String[]) props.get(HOST_NAMES);
        int[] ports = (int[]) props.get(PORTS);

        String hostname = (hostnames == null || hostnames.length < 1) ? null : hostnames[0];
        String port = (ports == null || ports.length < 1) ? null : String.valueOf(ports[0]);

        return sslHelper.getConnectionInfo(hostname, port);
    }

    /**
     * Receive notification when the contents of the shared library are changed.
     * This could happen, for example, if someone swaps out the mongo.jar with a newer version.
     * When this happens, just close the current Mongo instance and uninitialize ourself.
     */
    @Override
    public void libraryNotification() {
        closeMongoConnection();
    }

    @Override
    public void changeOccurred() {
        closeMongoConnection();

        // We also no longer care about changes to SSL config mongo was using
        if (sslHelper != null) {
            sslHelper.removeChangeListener(this);
        }

        // Tell all our listeners that something changed
        synchronized (changeListeners) {
            for (MongoChangeListener listener : changeListeners) {
                listener.changeOccurred();
            }
        }
    }

    private void closeMongoConnection() {
        lock.writeLock().lock();
        try {
            if (mongo != null)
                try {
                    mongo.getClass().getMethod("close").invoke(mongo);
                } catch (Exception x) {
                    // FindBugs complains if we don't put anything in this catch block and just rely on the generated FFDC.
                    if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled())
                        Tr.debug(this, tc, "unable to close mongo", x);
                } finally {
                    mongo = null;
                }
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Configure a mongo option.
     *
     * @param builder com.mongodb.MongoClientOptions.Builder
     * @param propName name of the config property.
     * @param type type of the config property.
     */
    @FFDCIgnore(Throwable.class)
    @Trivial
    private void set(Class<?> builderCls, Object builder, String propName,
                     Object value) throws IntrospectionException, IllegalArgumentException, IllegalAccessException, InvocationTargetException {
        try {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled())
                Tr.debug(this, tc, propName + '=' + value);

            Class<?> cls = MONGO_CLIENT_OPTIONS_TYPES.get(propName);
            // setter methods are just propName, no setPropName.
            Method method = builderCls.getMethod(propName, cls);
            // even though we told the config service that some of these props are Integers, they get converted to longs. Need
            // to convert them back to int so that our .invoke(..) method doesn't blow up.
            if (cls.equals(int.class) && value instanceof Long) {
                value = ((Long) value).intValue();
            }
            method.invoke(builder, value);
            return;

        } catch (Throwable x) {
            if (x instanceof InvocationTargetException)
                x = x.getCause();
            IllegalArgumentException failure = ignoreWarnOrFail(x, IllegalArgumentException.class, "CWKKD0010.prop.error", propName, MONGO, id, x);
            if (failure != null) {
                FFDCFilter.processException(failure, getClass().getName(), "394", this, new Object[] { value == null ? null : value.getClass(), value });
                throw failure;
            }
        }
    }

    /**
     * Configure the "readPreference" mongo option, which is a special case.
     *
     * @param options com.mongodb.MongoOptions
     * @param propName name of the config property.
     * @param type type of the config property.
     */
    @FFDCIgnore(Throwable.class)
    @Trivial
    private void setReadPreference(Class<?> mongoClientOptionsBuilderCls, Object builder,
                                   Object value) throws ClassNotFoundException, IllegalArgumentException, SecurityException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        try {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled())
                Tr.debug(this, tc, READ_PREFERENCE + '=' + value);

            Class<?> readPreferenceCls = mongoClientOptionsBuilderCls.getClassLoader().loadClass(MONGO_READPREFERENCE_CLS_STR);
            // Calls static ReadPreference.nearest() (or whatever conf is set to) to get a ReadPreference object
            value = readPreferenceCls.getMethod((String) value).invoke(readPreferenceCls);

            // Set static ReadPreference on Builder.
            mongoClientOptionsBuilderCls.getMethod(READ_PREFERENCE_METHOD_NAME, readPreferenceCls).invoke(builder, value);
        } catch (Throwable x) {
            if (x instanceof InvocationTargetException)
                x = x.getCause();
            IllegalArgumentException failure = ignoreWarnOrFail(x, IllegalArgumentException.class, "CWKKD0010.prop.error", READ_PREFERENCE, MONGO, id, x);
            if (failure != null) {
                FFDCFilter.processException(failure, getClass().getName(), "422", this);
                throw failure;
            }
        }
    }

    /**
     * Configure the "writeConcern" mongo option, which is a special case.
     *
     * @param options com.mongodb.MongoOptions
     * @param propName name of the config property.
     * @param type type of the config property.
     */
    @FFDCIgnore(Throwable.class)
    @Trivial
    private void setWriteConcern(Class<?> mongoClientOptionsBuilderCls, Object builder,
                                 Object value) throws ClassNotFoundException, IllegalArgumentException, SecurityException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        try {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled())
                Tr.debug(this, tc, WRITE_CONCERN + '=' + value);

            Class<?> writeConcernCls = mongoClientOptionsBuilderCls.getClassLoader().loadClass(MONGO_WRITECONCERN_CLS_STR);
            // Set value to the static class value
            value = writeConcernCls.getField((String) value).get(null);

            mongoClientOptionsBuilderCls.getMethod(WRITE_CONCERN_METHOD_NAME, writeConcernCls).invoke(builder, value);
        } catch (Throwable x) {
            if (x instanceof InvocationTargetException)
                x = x.getCause();
            IllegalArgumentException failure = ignoreWarnOrFail(x, IllegalArgumentException.class, "CWKKD0010.prop.error", WRITE_CONCERN, MONGO, id, x);
            if (failure != null) {
                FFDCFilter.processException(failure, getClass().getName(), "422", this);
                throw failure;
            }
        }
    }

    private void setSocketFactory(Class<?> mongoClientOptionsBuilderCls, Object builder, Map<String, Object> connectionInfo) throws Exception {
        SSLSocketFactory sslSocketFactory = null;
        Object sslService = sslConfigurationRef.getService();

        sslSocketFactory = sslHelper.getSSLSocketFactory(sslService, connectionInfo);

        // Create a mongo client with this socket factory
        Method socketFactoryMethod = mongoClientOptionsBuilderCls.getMethod(SOCKET_FACTORY_METHOD_NAME, SocketFactory.class);
        socketFactoryMethod.invoke(builder, sslSocketFactory);
    }

    /**
     * Declarative Services method for setting the shared library service reference
     *
     * @param ref reference to the service
     */
    protected void setLibrary(ServiceReference<Library> ref) {
        libraryRef.setReference(ref);
    }

    /**
     * Declarative Services method for unsetting the shared library service reference
     *
     * @param ref reference to the service
     */
    protected void unsetLibrary(ServiceReference<Library> ref) {
        libraryRef.unsetReference(ref);
    }

    /**
     * Declarative Services method for setting the SSL Support service reference
     *
     * @param ref reference to the service
     */
    protected void setSsl(ServiceReference<Object> reference) {
        sslConfigurationRef.setReference(reference);
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, "sslRef set to " + reference.getProperty("config.displayId"));
        }
    }

    /**
     * Declarative Services method for unsetting the SSL Support service reference
     *
     * @param ref reference to the service
     */
    protected void unsetSsl(ServiceReference<Object> reference) {
        sslConfigurationRef.unsetReference(reference);
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, "sslRef unset");
        }
    }

    /**
     * Declarative Services method for setting the KeyStore service reference
     *
     * @param ref reference to the service
     */
    protected void setKeyStoreService(ServiceReference<Object> ref) {
        keyStoreServiceRef.setReference(ref);
    }

    /**
     * Declarative Services method for unsetting the KeyStore service reference
     *
     * @param ref reference to the service
     */
    protected void unsetKeyStoreService(ServiceReference<Object> ref) {
        keyStoreServiceRef.unsetReference(ref);
    }

    protected void setMongoSslHelper(MongoSslHelper sslHelper) {
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, "Mongo helper set");
        }
        this.sslHelper = sslHelper;
    }

    protected void unsetMongoSslHelper(MongoSslHelper sslHelper) {
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, "Mongo helper unset");
        }
        this.sslHelper = null;
    }

    /**
     * Validate combination of security parameters for certificate authentication. If
     * useCertificateAuthentication is specified SSL must be enabled, an ssslRef must be
     * specified and user and password should not be specified.
     */
    private void assertValidSSLConfig() {
        final boolean trace = TraceComponent.isAnyTracingEnabled();

        boolean sslEnabled = (((Boolean) props.get(SSL_ENABLED)) == null) ? false : (Boolean) props.get(SSL_ENABLED);
        boolean sslRefExists = ((props.get(SSL_REF)) == null) ? false : true;

        if (sslRefExists && !sslEnabled) {
            if (trace && tc.isDebugEnabled()) {
                // sslRef property set in the server.xml but sslEnabled not set to true.
                Tr.error(tc, "CWKKD0024.ssl.sslref.no.ssl", MONGO, id);
            }
            throw new RuntimeException(Utils.getMessage("CWKKD0024.ssl.sslref.no.ssl", MONGO, id));
        }

        if (sslEnabled) {
            // we should have the ssl-1.0 feature selected
            if (sslHelper == null) {
                throw new RuntimeException(Utils.getMessage("CWKKD0015.ssl.feature.missing", MONGO, id));
            }

            if (useCertAuth) {

                if (!sslEnabled) {
                    // SSL not enabled, so shouldn't be using certificate authentication
                    throw new RuntimeException(Utils.getMessage("CWKKD0019.ssl.certificate.no.ssl", MONGO, id));
                }
                if (props.get(USER) != null || props.get(PASSWORD) != null) {
                    // shouldn't be using userid and pasword with certificate authentication
                    throw new RuntimeException(Utils.getMessage("CWKKD0018.ssl.user.pswd.certificate", MONGO, id));
                }
            }
        }
    }

    /**
     * This private worker method will assert that the configured shared library has the correct level
     * of the mongoDB java driver. It will throw a RuntimeException if it is :
     * <li> unable to locate com.mongodb.Mongo.class or com.mongodb.MongoDB.class
     * <li> if the configured library is less than the min supported level
     */
    private void assertLibrary(ClassLoader loader, boolean sslEnabled) {
        Class<?> mongoClientCls = loadClass(loader, MONGO_CLIENT_CLS_STR);
        int minor = -1, major = -1;
        if (mongoClientCls == null) {
            Class<?> mongoCls = loadClass(loader, MONGO_CLS_STR);
            // If we can't find either class, die
            if (mongoCls == null) {
                // CWKKD0014.missing.driver=CWKKD0014E: The {0} service was unable to locate the required MongoDB driver classes at shared library {1}.
                throw new RuntimeException(Utils.getMessage("CWKKD0014.missing.driver", MONGO, libraryRef.getService().id()));
            }
            major = getVersionFromMongo(mongoCls, MONGO_MAJOR_VERSION_STATIC_VAR_NAME);
            minor = getVersionFromMongo(mongoCls, MONGO_MINOR_VERSION_STATIC_VAR_NAME);
        } else {
            major = getVersionFromMongoClient(mongoClientCls, MONGO_MAJOR_VERSION_METHOD_NAME);
            minor = getVersionFromMongoClient(mongoClientCls, MONGO_MINOR_VERSION_METHOD_NAME);
        }

        if (useCertAuth && ((major < MIN_MAJOR) || (major == MIN_MAJOR && minor < CERT_AUTH_MIN_MINOR))) {
            Tr.error(tc, "CWKKD0023.ssl.certauth.incompatible.driver", MONGO, id, libraryRef.getService().id(),
                     MIN_MAJOR + "." + CERT_AUTH_MIN_MINOR, major + "." + minor);
            throw new RuntimeException(Utils.getMessage("CWKKD0023.ssl.certauth.incompatible.driver", MONGO, id, libraryRef.getService().id(),
                                                        MIN_MAJOR + "." + CERT_AUTH_MIN_MINOR, major + "." + minor));
        }

        if (sslEnabled && ((major < MIN_MAJOR) || (major == MIN_MAJOR && minor < SSL_MIN_MINOR))) {
            Tr.error(tc, "CWKKD0017.ssl.incompatible.driver", MONGO, id, libraryRef.getService().id(),
                     MIN_MAJOR + "." + SSL_MIN_MINOR, major + "." + minor);
            throw new RuntimeException(Utils.getMessage("CWKKD0017.ssl.incompatible.driver", MONGO, id, libraryRef.getService().id(),
                                                        MIN_MAJOR + "." + SSL_MIN_MINOR, major + "." + minor));
        }

        if (major > MIN_MAJOR || (major == MIN_MAJOR && minor >= MIN_MINOR)) {
            return;
        }
        // CWKKD0013.unsupported.driver=CWKKD0013E: The {0} service encountered down level version of the MongoDB
        //      driver at shared library {1}. Expected a minimum level of {2}, but found {3}.
        Tr.error(tc, "CWKKD0013.unsupported.driver", MONGO, libraryRef.getService().id(),
                 MIN_MAJOR + "." + MIN_MINOR, major + "." + minor);
        throw new RuntimeException(Utils.getMessage("CWKKD0013.unsupported.driver", MONGO, libraryRef.getService().id(),
                                                    MIN_MAJOR + "." + MIN_MINOR, major + "." + minor));

    }

    /**
     * Register a listener to be called when a change occurs which should invalidate existing DB connections, but which does not cause this component to become invalidated (which
     * would be communicated by references to it being unbound)
     */
    public void registerChangeListener(MongoChangeListener listener) {
        changeListeners.add(listener);
    }

    /**
     * Remove a listener registered with {@link #registerChangeListener(MongoChangeListener)}
     */
    public void unregisterChangeListener(MongoChangeListener listener) {
        changeListeners.remove(listener);
    }

    @FFDCIgnore(Exception.class)
    private int getVersionFromMongo(Class<?> mongo, String fieldName) {
        try {
            return mongo.getField(fieldName).getInt(null);
        } catch (Exception e) {
            if (tc.isDebugEnabled())
                Tr.debug(this, tc, "unable to getVersionFromMongo " + mongo + "." + fieldName + " " + e.getMessage());
        }
        return -1;
    }

    @FFDCIgnore(Exception.class)
    private int getVersionFromMongoClient(Class<?> mongoClient, String methodName) {
        try {
            return (Integer) mongoClient.getMethod(methodName).invoke(null);
        } catch (Exception e) {
            if (tc.isDebugEnabled())
                Tr.debug(this, tc, "unable to getVersionFromMongoClient " + mongoClient + "." + methodName + "() " + e.getMessage());
        }
        return -1;
    }

    @FFDCIgnore(Exception.class)
    private Class<?> loadClass(ClassLoader loader, String clsStr) {
        try {
            return loader.loadClass(clsStr);
        } catch (Exception e) {
            if (tc.isDebugEnabled())
                Tr.debug(this, tc, "unable to load " + clsStr + " from " + loader + " " + e.getMessage());
        }
        return null;
    }
}
