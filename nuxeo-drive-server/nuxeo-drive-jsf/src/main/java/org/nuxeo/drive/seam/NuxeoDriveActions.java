/*
 * (C) Copyright 2012 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Olivier Grisel <ogrisel@nuxeo.com>
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
package org.nuxeo.drive.seam;

import java.io.Serializable;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.faces.context.FacesContext;
import javax.servlet.ServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.Factory;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Install;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.contexts.Context;
import org.jboss.seam.contexts.Contexts;
import org.nuxeo.drive.adapter.FileSystemItem;
import org.nuxeo.drive.hierarchy.userworkspace.adapter.UserWorkspaceHelper;
import org.nuxeo.drive.service.FileSystemItemAdapterService;
import org.nuxeo.drive.service.NuxeoDriveManager;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.security.SecurityException;
import org.nuxeo.ecm.platform.ui.web.api.NavigationContext;
import org.nuxeo.ecm.platform.web.common.vh.VirtualHostHelper;
import org.nuxeo.ecm.tokenauth.service.TokenAuthenticationService;
import org.nuxeo.ecm.user.center.UserCenterViewManager;
import org.nuxeo.runtime.api.Framework;

/**
 * @since 5.7
 */
@Name("nuxeoDriveActions")
@Scope(ScopeType.CONVERSATION)
@Install(precedence = Install.FRAMEWORK)
public class NuxeoDriveActions implements Serializable {

    private static final Log log = LogFactory.getLog(NuxeoDriveActions.class);

    protected static final String IS_UNDER_SYNCHRONIZATION_ROOT = "nuxeoDriveIsUnderSynchronizationRoot";

    protected static final String CURRENT_SYNCHRONIZATION_ROOT = "nuxeoDriveCurrentSynchronizationRoot";

    private static final long serialVersionUID = 1L;

    public static final String NXDRIVE_PROTOCOL = "nxdrive";

    public static final String PROTOCOL_COMMAND_EDIT = "edit";

    @In(required = false)
    NavigationContext navigationContext;

    @In(required = false)
    CoreSession documentManager;

    @In(required = false, create = true)
    UserCenterViewManager userCenterViews;

    @Factory(value = CURRENT_SYNCHRONIZATION_ROOT, scope = ScopeType.EVENT)
    public DocumentModel getCurrentSynchronizationRoot() throws ClientException {
        if (navigationContext == null || documentManager == null) {
            return null;
        }
        // Use the event context as request cache
        Context cache = Contexts.getEventContext();
        Boolean isUnderSync = (Boolean) cache.get(IS_UNDER_SYNCHRONIZATION_ROOT);
        if (isUnderSync == null) {
            NuxeoDriveManager driveManager = Framework.getLocalService(NuxeoDriveManager.class);
            Set<IdRef> references = driveManager.getSynchronizationRootReferences(documentManager);
            DocumentModelList path = navigationContext.getCurrentPath();
            DocumentModel root = null;
            // list is ordered such as closest synchronized ancestor is
            // considered the current synchronization root
            for (DocumentModel parent : path) {
                if (references.contains(parent.getRef())) {
                    root = parent;
                    break;
                }
            }
            cache.set(CURRENT_SYNCHRONIZATION_ROOT, root);
            cache.set(IS_UNDER_SYNCHRONIZATION_ROOT, root != null);
        }
        return (DocumentModel) cache.get(CURRENT_SYNCHRONIZATION_ROOT);
    }

    @Factory(value = "canEditCurrentDocument", scope = ScopeType.EVENT)
    public boolean canEditCurrentDocument() throws ClientException {
        if (getCurrentSynchronizationRoot() == null
                || navigationContext == null || documentManager == null
                || navigationContext.getCurrentDocument() == null) {
            return false;
        }
        BlobHolder blobHolder = navigationContext.getCurrentDocument().getAdapter(
                BlobHolder.class);
        return (blobHolder != null && blobHolder.getBlob() != null);
    }

    /**
     * {@link #NXDRIVE_PROTOCOL} must be handled by a protocol handler
     * configured on the client side (either on the browser, or on the OS).
     *
     * @return Drive edit URL in the form "{@link #NXDRIVE_PROTOCOL}://
     *         {@link #PROTOCOL_COMMAND_EDIT}
     *         /protocol/server[:port]/webappName/nxdoc/repoName/docRef"
     * @throws ClientException
     *
     */
    public String getDriveEditURL() throws ClientException {

        FileSystemItemAdapterService fsAdapterService = Framework.getLocalService(FileSystemItemAdapterService.class);
        // TODO: optim: add a new method to FileSystemItemAdapterService to
        // quickly compute the fsitem id from a doc (without having to
        // recursively adapt the parents)
        FileSystemItem fileSystemItem = fsAdapterService.getFileSystemItem(navigationContext.getCurrentDocument());
        if (fileSystemItem == null) {
            log.warn(String.format(
                    "Failed to adapt '%s' to generate drive edit link",
                    navigationContext.getCurrentDocument().getTitle()));
            return "";
        }
        String fsItemId = fileSystemItem.getId();
        ServletRequest servletRequest = (ServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
        String baseURL = VirtualHostHelper.getBaseURL(servletRequest);
        StringBuffer sb = new StringBuffer();
        sb.append(NXDRIVE_PROTOCOL).append("://");
        sb.append(PROTOCOL_COMMAND_EDIT).append("/");
        sb.append(baseURL.replaceFirst("://", "/"));
        sb.append("fsitem/");
        sb.append(fsItemId);
        return sb.toString();
    }

    @Factory(value = "canSynchronizeCurrentDocument", scope = ScopeType.EVENT)
    public boolean getCanSynchronizeCurrentDocument() throws ClientException {
        if (navigationContext == null) {
            return false;
        }
        DocumentModel currentDocument = navigationContext.getCurrentDocument();
        if (!currentDocument.isFolder()) {
            return false;
        }
        boolean hasPermission = documentManager.hasPermission(
                currentDocument.getRef(), SecurityConstants.ADD_CHILDREN);
        if (!hasPermission) {
            return false;
        }
        return getCurrentSynchronizationRoot() == null;
    }

    @Factory(value = "canUnSynchronizeCurrentDocument", scope = ScopeType.EVENT)
    public boolean getCanUnSynchronizeCurrentDocument() throws ClientException {
        if (navigationContext == null) {
            return false;
        }
        DocumentModel currentDocument = navigationContext.getCurrentDocument();
        DocumentRef currentDocRef = currentDocument.getRef();
        DocumentModel currentSyncRoot = getCurrentSynchronizationRoot();
        if (currentSyncRoot == null) {
            return false;
        }
        return currentDocRef.equals(currentSyncRoot.getRef());
    }

    @Factory(value = "canNavigateToCurrentSynchronizationRoot", scope = ScopeType.EVENT)
    public boolean getCanNavigateToCurrentSynchronizationRoot()
            throws ClientException {
        if (navigationContext == null) {
            return false;
        }
        DocumentModel currentDocument = navigationContext.getCurrentDocument();
        DocumentRef currentDocRef = currentDocument.getRef();
        DocumentModel currentSyncRoot = getCurrentSynchronizationRoot();
        if (currentSyncRoot == null) {
            return false;
        }
        return !currentDocRef.equals(currentSyncRoot.getRef());
    }

    @Factory(value = "currentDocumentUserWorkspace", scope = ScopeType.PAGE)
    public boolean isCurrentDocumentUserWorkspace() throws ClientException {
        if (navigationContext == null) {
            return false;
        }
        DocumentModel currentDocument = navigationContext.getCurrentDocument();
        return UserWorkspaceHelper.isUserWorkspace(currentDocument);
    }

    public String synchronizeCurrentDocument() throws ClientException,
            SecurityException {
        NuxeoDriveManager driveManager = Framework.getLocalService(NuxeoDriveManager.class);
        Principal principal = documentManager.getPrincipal();
        String userName = principal.getName();
        DocumentModel newSyncRoot = navigationContext.getCurrentDocument();
        driveManager.registerSynchronizationRoot(principal, newSyncRoot,
                documentManager);
        TokenAuthenticationService tokenService = Framework.getLocalService(TokenAuthenticationService.class);
        boolean hasOneNuxeoDriveToken = false;
        for (DocumentModel token : tokenService.getTokenBindings(userName)) {
            if ("Nuxeo Drive".equals(token.getPropertyValue("authtoken:applicationName"))) {
                hasOneNuxeoDriveToken = true;
                break;
            }
        }
        if (hasOneNuxeoDriveToken) {
            return null;
        } else {
            // redirect to user center
            userCenterViews.setCurrentViewId("userCenterNuxeoDrive");
            return "view_home";
        }
    }

    public void unsynchronizeCurrentDocument() throws ClientException {
        NuxeoDriveManager driveManager = Framework.getLocalService(NuxeoDriveManager.class);
        Principal principal = documentManager.getPrincipal();
        DocumentModel syncRoot = navigationContext.getCurrentDocument();
        driveManager.unregisterSynchronizationRoot(principal, syncRoot,
                documentManager);
    }

    public String navigateToCurrentSynchronizationRoot() throws ClientException {
        DocumentModel currentRoot = getCurrentSynchronizationRoot();
        if (currentRoot == null) {
            return "";
        }
        return navigationContext.navigateToDocument(currentRoot);
    }

    public DocumentModelList getSynchronizationRoots() throws ClientException {
        DocumentModelList syncRoots = new DocumentModelListImpl();
        NuxeoDriveManager driveManager = Framework.getLocalService(NuxeoDriveManager.class);
        Set<IdRef> syncRootRefs = driveManager.getSynchronizationRootReferences(documentManager);
        for (IdRef syncRootRef : syncRootRefs) {
            syncRoots.add(documentManager.getDocument(syncRootRef));
        }
        return syncRoots;
    }

    public void unsynchronizeRoot(DocumentModel syncRoot)
            throws ClientException {
        NuxeoDriveManager driveManager = Framework.getLocalService(NuxeoDriveManager.class);
        Principal principal = documentManager.getPrincipal();
        driveManager.unregisterSynchronizationRoot(principal, syncRoot,
                documentManager);
    }

    public List<DesktopPackageDefinition> getClientPackages() {
        FacesContext ctx = FacesContext.getCurrentInstance();
        Set<String> paths = ctx.getExternalContext().getResourcePaths(
                "/nuxeo-drive");
        if (paths == null) {
            return Collections.emptyList();
        }
        String baseURL = VirtualHostHelper.getBaseURL((ServletRequest) ctx.getExternalContext().getRequest());
        List<DesktopPackageDefinition> packages = new ArrayList<DesktopPackageDefinition>();
        for (String path : paths) {
            packages.add(new DesktopPackageDefinition(path, baseURL));
        }
        return packages;
    }

}
