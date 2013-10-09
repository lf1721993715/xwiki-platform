/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.wiki.descriptor.internal.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.event.WikiDeletedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.ObservationManager;
import org.xwiki.wiki.WikiDescriptor;
import org.xwiki.wiki.descriptor.internal.DefaultWikiDescriptor;
import org.xwiki.wiki.descriptor.internal.builder.WikiDescriptorBuilder;
import org.xwiki.wiki.descriptor.internal.builder.WikiDescriptorBuilderException;
import org.xwiki.wiki.descriptor.internal.document.WikiDescriptorDocumentHelper;
import org.xwiki.wiki.manager.WikiManager;
import org.xwiki.wiki.manager.WikiManagerException;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.util.Util;

/**
 * Default implementation for {@link WikiManager}.
 * @version $Id$
 * @since 5.3M1
 */
@Component
@Singleton
public class DefaultWikiManager implements WikiManager
{
    @Inject
    private Provider<XWikiContext> xcontextProvider;

    @Inject
    private WikiDescriptorCache cache;

    @Inject
    private WikiDescriptorDocumentHelper descriptorDocumentHelper;

    @Inject
    private ContextualLocalizationManager localizationManager;

    @Inject
    private ObservationManager observationManager;

    @Inject
    private Logger logger;

    @Inject
    private WikiDescriptorBuilder wikiDescriptorBuilder;

    @Override
    public WikiDescriptor create(String wikiId, String wikiAlias) throws WikiManagerException
    {
        // Check that the wiki Id is available
        if (!idAvailable(wikiId)) {
            throw new WikiManagerException("wiki id is not valid");
        }

        XWikiContext context = xcontextProvider.get();
        XWiki xwiki = context.getWiki();

        // Create database/schema
        try {
            xwiki.getStore().createWiki(wikiId, context);
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new WikiManagerException(localizationManager.getTranslationPlain("wiki.databasecreation"));
        }

        // Init database/schema
        try {
            xwiki.updateDatabase(wikiId, true, true, context);
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new WikiManagerException(localizationManager.getTranslationPlain("wiki.databaseupdate"));
        }

        // Create the descriptor
        DefaultWikiDescriptor descriptor = new DefaultWikiDescriptor(wikiId, wikiAlias);

        try {
            // Build the document
            XWikiDocument descriptorDocument = wikiDescriptorBuilder.buildDescriptorDocument(descriptor);
            // Save the document
            xwiki.getStore().saveXWikiDoc(descriptorDocument, context);
            // Add the document to the descriptor
            descriptor.setDocumentReference(descriptorDocument.getDocumentReference());
            // Add the descriptor to the cache
            cache.add(descriptor);
        } catch (WikiDescriptorBuilderException e) {
            throw new WikiManagerException("Failed to build the descriptor document.", e);
        } catch (XWikiException e) {
            throw new WikiManagerException("Failed to save the descriptor document.", e);
        }

        return descriptor;
    }

    @Override
    public void delete(String wikiId) throws WikiManagerException
    {
        XWikiContext context = xcontextProvider.get();
        XWiki xwiki = context.getWiki();

        // Check if we try to delete the main wiki
        if (wikiId.equals(getMainWikiId())) {
            throw new WikiManagerException("can't delete main wiki");
        }

        // Delete the database
        try {
            xwiki.getStore().deleteWiki(wikiId, context);
        } catch (XWikiException e) {
            throw new WikiManagerException("can't delete database");
        }

        // Delete the descriptor document
        DefaultWikiDescriptor descriptor = (DefaultWikiDescriptor) getById(wikiId);
        try {
            XWikiDocument descriptorDocument = getDocument(descriptor.getDocumentReference());
            xwiki.deleteDocument(descriptorDocument, context);
        } catch (XWikiException e) {
            throw new WikiManagerException("can't delete descriptor document");
        }

        // Remove the descriptor from the caches
        cache.remove(descriptor);

        // Send an event
        observationManager.notify(new WikiDeletedEvent(wikiId), descriptor);
    }

    @Override
    public WikiDescriptor getByAlias(String wikiAlias) throws WikiManagerException
    {
        WikiDescriptor wiki = cache.getFromAlias(wikiAlias);

        // If not found in the cache then query the wiki and add to the cache if found.
        //
        // Note that an alternative implementation would have been to find all Wiki Descriptors at startup but this
        // would have meant keeping them all in memory at once. Since we want to be able to scale to any number of
        // subwikis we only cache the most used one. This allows inactive wikis to not take up any memory for example.
        // Note that In order for performance to be maximum it also means we need to have a cache size at least as
        // large as the max # of wikis being used at once.
        if (wiki == null) {
            XWikiDocument document = descriptorDocumentHelper.findXWikiServerClassDocument(wikiAlias);
            if (document != null) {
                wiki = buildDescriptorFromDocument(document);
            }
        }

        return wiki;
    }

    @Override
    public WikiDescriptor getById(String wikiId) throws WikiManagerException
    {
        WikiDescriptor wiki = cache.getFromId(wikiId);

        if (wiki == null) {
            // Try to load a page named XWiki.XWikiServer<wikiId>
            XWikiDocument document = descriptorDocumentHelper.getDocumentFromWikiId(wikiId);
            if (!document.isNew()) {
                wiki = buildDescriptorFromDocument(document);
            }
        }

        return wiki;
    }

    private DefaultWikiDescriptor buildDescriptorFromDocument(XWikiDocument document)
    {
        DefaultWikiDescriptor descriptor = wikiDescriptorBuilder.buildDescriptorObject(
                document.getXObjects(DefaultWikiDescriptor.SERVER_CLASS), document);
        // Add to the cache
        if (descriptor != null) {
            cache.add(descriptor);
        }
        return descriptor;
    }

    @Override
    public Collection<WikiDescriptor> getAll() throws WikiManagerException
    {
        // Note: Ideally to improve performance we could imagine loading all XWikiServerClasses at initialization time
        // (in initialize()) and thereafter only use the cache. The problem with this approach is that our Cache will
        // need to be unbounded which is not the case right now. This would mean being able to put all descriptors in
        // the cache and thus it might not scale if there were a very large number of wikis.

        List<WikiDescriptor> result = new ArrayList<WikiDescriptor>();

        try {
            List<XWikiDocument> documents = descriptorDocumentHelper.getAllXWikiServerClassDocument();
            for (XWikiDocument document : documents) {
                // Extract the Wiki
                result.add(buildDescriptorFromDocument(document));
            }
        } catch (Exception e) {
            throw new WikiManagerException("Failed to locate XWiki.XWikiServerClass documents", e);
        }

        return result;
    }

    @Override
    public boolean exists(String wikiId) throws WikiManagerException {
        return getById(wikiId) != null;
    }

    @Override
    public boolean idAvailable(String wikiId) throws WikiManagerException {
        //TODO: look if the id is valid and free (the database does not already exists, for example)
        String wikiForbiddenList = xcontextProvider.get().getWiki().Param("xwiki.virtual.reserved_wikis");
        return !exists(wikiId) && !Util.contains(wikiId, wikiForbiddenList, ", ");
    }

    @Override
    public WikiDescriptor getMainWikiDescriptor() throws WikiManagerException
    {
        return getById(getMainWikiId());
    }

    @Override
    public String getMainWikiId()
    {
        return xcontextProvider.get().getMainXWiki();
    }

    private XWikiDocument getDocument(DocumentReference reference) throws WikiManagerException
    {
        XWikiContext context = xcontextProvider.get();
        com.xpn.xwiki.XWiki xwiki = context.getWiki();
        try {
            return xwiki.getDocument(reference, context);
        } catch (XWikiException e) {
            throw new WikiManagerException(String.format(
                    "Failed to get document [%s] containing a XWiki.XWikiServerClass object", reference), e);
        }
    }

}
