-include= ~${workspace}/cnf/resources/bnd/feature.props
symbolicName=com.ibm.websphere.appserver.org.eclipse.microprofile.metrics-2.0
WLP-DisableAllFeatures-OnConflict: false
singleton=true
-features=io.openliberty.mpCompatible-0.0
-bundles=com.ibm.websphere.org.eclipse.microprofile.metrics.2.0; location:="dev/api/stable/,lib/"; mavenCoordinates="org.eclipse.microprofile.metrics:microprofile-metrics-api:2.0.0"
kind=ga
edition=core
