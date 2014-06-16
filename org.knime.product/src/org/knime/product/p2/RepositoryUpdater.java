/*
 * ------------------------------------------------------------------------
 *  Copyright by KNIME GmbH, Konstanz, Germany
 *  Website: http://www.knime.org; Email: contact@knime.org
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME GMBH herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   21.03.2014 (thor): created
 */
package org.knime.product.p2;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.EventObject;
import java.util.regex.Pattern;

import org.eclipse.equinox.internal.provisional.p2.core.eventbus.IProvisioningEventBus;
import org.eclipse.equinox.internal.provisional.p2.core.eventbus.ProvisioningListener;
import org.eclipse.equinox.internal.provisional.p2.repository.RepositoryEvent;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.repository.IRepository;
import org.eclipse.equinox.p2.repository.IRepositoryManager;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.knime.core.node.KNIMEConstants;
import org.knime.core.node.NodeLogger;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * Singleton class that updates artifact repository URIs to include the instance ID. The singleton registers itself as a
 * listener to the p2 event bus so that it can react on changes to repository URIs immediately.
 *
 * @author Thorsten Meinl, KNIME.com, Zurich, Switzerland
 * @since 2.10
 */
@SuppressWarnings("restriction")
public class RepositoryUpdater implements ProvisioningListener {
    /**
     * Singleton instance.
     */
    public static final RepositoryUpdater INSTANCE = new RepositoryUpdater();

    private RepositoryUpdater() {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        ServiceReference<IProvisioningAgent> ref = context.getServiceReference(IProvisioningAgent.class);
        if (ref != null) {
            IProvisioningAgent agent = context.getService(ref);
            try {
                IProvisioningEventBus eventBus =
                    (IProvisioningEventBus)agent.getService(IProvisioningEventBus.SERVICE_NAME);
                if (eventBus != null) {
                    // is null if started from the SDK
                    eventBus.addListener(this);
                }
            } finally {
                context.ungetService(ref);
            }
        }
    }

    /**
     * Updates the URLs of all artifact repository by adding the KNIME ID to them.
     */
    public void updateArtifactRepositoryURLs() {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        ServiceReference<IProvisioningAgent> ref = context.getServiceReference(IProvisioningAgent.class);
        if (ref != null) {
            IProvisioningAgent agent = context.getService(ref);
            try {
                IArtifactRepositoryManager repoManager =
                    (IArtifactRepositoryManager)agent.getService(IArtifactRepositoryManager.SERVICE_NAME);
                if (repoManager != null) {
                    // is null if started from the SDK
                    updateArtifactRepositoryURLs(repoManager, IRepositoryManager.REPOSITORIES_NON_LOCAL);
                    updateArtifactRepositoryURLs(repoManager, IRepositoryManager.REPOSITORIES_DISABLED);
                }
            } finally {
                context.ungetService(ref);
            }
        }
    }

    /**
     * Updates all URI that are known to the given repository manager. The flags can be used to select certain
     * repositories, see {@link IArtifactRepositoryManager#getKnownRepositories(int)}.
     *
     * @param repoManager an artifact repository manager
     * @param flags flags for getting the repositories
     */
    private void updateArtifactRepositoryURLs(final IArtifactRepositoryManager repoManager, final int flags) {
        for (URI uri : repoManager.getKnownRepositories(flags)) {
            updateArtifactRepositoryURL(repoManager, uri);
        }
    }

    /**
     * Updates the URI of a single repository. An update is only performed if the URI does not already contains the
     * KNIME ID (see {@link #urlContainsID(URI)}). Only HTTP(S) URIs from knime.org or knime.com hosts are updated.
     *
     * @param repoManager an artifact repository manager
     * @param uri a URI to a repository, must be known by the repository manager
     */
    private void updateArtifactRepositoryURL(final IArtifactRepositoryManager repoManager, final URI uri) {
        if (uri.getScheme().startsWith("http") && isKnimeURI(uri) && !urlContainsID(uri)) {
            boolean enabled = repoManager.isEnabled(uri);
            String knidPath =
                (uri.getPath().endsWith("/") ? "" : "/") + "knid=" + KNIMEConstants.getKNIMEInstanceID() + "/";
            try {
                URI newUri =
                    new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), uri.getPath() + knidPath,
                        uri.getQuery(), uri.getFragment());
                repoManager.addRepository(newUri);
                repoManager.setEnabled(newUri, enabled);

                repoManager.removeRepository(uri);
            } catch (URISyntaxException ex) {
                NodeLogger.getLogger(getClass()).error(
                    "Error while updating artifact repository URI '" + uri.toString() + "': " + ex.getMessage(), ex);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notify(final EventObject o) {
        if (o instanceof RepositoryEvent) {
            RepositoryEvent event = (RepositoryEvent)o;
            if ((event.getKind() == RepositoryEvent.ADDED) && (event.getRepositoryType() == IRepository.TYPE_ARTIFACT)
                && !urlContainsID(event.getRepositoryLocation())) {

                // update artifact repository location when a new repository is added
                updateNewArtifactRepository(event);
            } else if ((event.getKind() == RepositoryEvent.REMOVED)
                && (event.getRepositoryType() == IRepository.TYPE_METADATA)
                && isKnimeURI(event.getRepositoryLocation())) {

                // remove modified artifact repository when the corresponding metadata repository is removed
                removeOutdatedArtifactRepository(event);
            }
        }
    }

    private void removeOutdatedArtifactRepository(final RepositoryEvent event) {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        ServiceReference<IProvisioningAgent> ref = context.getServiceReference(IProvisioningAgent.class);
        if (ref != null) {
            IProvisioningAgent agent = context.getService(ref);
            try {
                IArtifactRepositoryManager repoManager =
                    (IArtifactRepositoryManager)agent.getService(IArtifactRepositoryManager.SERVICE_NAME);

                if (repoManager != null) {
                    URI removedMetadatalocation = event.getRepositoryLocation();
                    for (URI uri : repoManager.getKnownRepositories(IRepositoryManager.REPOSITORIES_NON_LOCAL)) {
                        if (urlContainsID(uri) && uri.toString().startsWith(removedMetadatalocation.toString())) {
                            repoManager.removeRepository(uri);
                        }
                    }
                    for (URI uri : repoManager.getKnownRepositories(IRepositoryManager.REPOSITORIES_DISABLED)) {
                        if (urlContainsID(uri) && uri.toString().startsWith(removedMetadatalocation.toString())) {
                            repoManager.removeRepository(uri);
                        }
                    }
                }
            } finally {
                context.ungetService(ref);
            }
        }
    }

    private void updateNewArtifactRepository(final RepositoryEvent event) {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        ServiceReference<IProvisioningAgent> ref = context.getServiceReference(IProvisioningAgent.class);
        if (ref != null) {
            IProvisioningAgent agent = context.getService(ref);
            try {
                IArtifactRepositoryManager repoManager =
                    (IArtifactRepositoryManager)agent.getService(IArtifactRepositoryManager.SERVICE_NAME);
                if (repoManager != null) {
                    updateArtifactRepositoryURL(repoManager, event.getRepositoryLocation());
                }
            } finally {
                context.ungetService(ref);
            }
        }
    }

    private static final Pattern KNID_PATTERN = Pattern
        .compile("/knid=[0-9a-fA-F]{2,2}-[0-9a-fA-F]{16,16}(?:-[0-9a-fA-F]+){0,}/");

    private static boolean urlContainsID(final URI uri) {
        return KNID_PATTERN.matcher(uri.getPath()).find();
    }

    private static final Pattern KNIME_HOST_PATTERN = Pattern.compile("^(?:www|tech|update)\\.knime\\.(?:org|com)$");

    private static boolean isKnimeURI(final URI uri) {
        return KNIME_HOST_PATTERN.matcher(uri.getHost()).matches();
    }
}