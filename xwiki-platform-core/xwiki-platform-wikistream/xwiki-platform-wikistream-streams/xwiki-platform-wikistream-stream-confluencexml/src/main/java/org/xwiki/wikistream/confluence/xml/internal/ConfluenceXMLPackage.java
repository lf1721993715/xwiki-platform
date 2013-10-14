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
package org.xwiki.wikistream.confluence.xml.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.commons.lang3.math.NumberUtils;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.wikistream.WikiStreamException;
import org.xwiki.wikistream.input.InputSource;
import org.xwiki.wikistream.input.InputStreamInputSource;
import org.xwiki.xml.stax.StAXUtils;

public class ConfluenceXMLPackage
{
    public static final String KEY_SPACE_NAME = "name";

    public static final String KEY_SPACE_DESCRIPTION = "description";

    public static final String KEY_PAGE_PARENT = "parent";

    public static final String KEY_PAGE_SPACE = "space";

    public static final String KEY_PAGE_TITLE = "title";

    public static final String KEY_PAGE_CONTENTS = "bodyContents";

    public static final String KEY_PAGE_CREATION_AUTHOR = "creatorName";

    public static final String KEY_PAGE_CREATION_DATE = "creationDate";

    public static final String KEY_PAGE_REVISION = "version";

    public static final String KEY_PAGE_REVISION_AUTHOR = "lastModifierName";

    public static final String KEY_PAGE_REVISION_DATE = "lastModificationDate";

    public static final String KEY_PAGE_REVISION_COMMENT = "versionComment";

    public static final String KEY_PAGE_REVISIONS = "historicalVersions";

    public static final String KEY_PAGE_CONTENT_STATUS = "contentStatus";

    public static final String KEY_PAGE_BODY = "body";

    public static final String KEY_PAGE_BODY_TYPE = "bodyType";

    public static final String KEY_ATTACHMENT_NAME = "fileName";

    public static final String KEY_ATTACHMENT_CONTENT_TYPE = "contentType";

    public static final String KEY_ATTACHMENT_CONTENT = "content";

    public static final String KEY_ATTACHMENT_CREATION_AUTHOR = "creatorName";

    public static final String KEY_ATTACHMENT_CREATION_DATE = "creationDate";

    public static final String KEY_ATTACHMENT_REVISION_AUTHOR = "lastModifierName";

    public static final String KEY_ATTACHMENT_REVISION_DATE = "lastModificationDate";

    public static final String KEY_ATTACHMENT_CONTENT_SIZE = "fileSize";

    public static final String KEY_ATTACHMENT_REVISION_COMMENT = "comment";

    public static final String KEY_ATTACHMENT_REVISION = "attachmentVersion";

    public static final String KEY_ATTACHMENT_DTO = "imageDetailsDTO";

    /**
     * 2012-03-07 17:16:48.158
     */
    public static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy.MM.dd G 'at' HH:mm:ss.SSS");

    private File directory;

    private File entities;

    private File descriptor;

    private File tree;

    private Map<Integer, Set<Integer>> pages = new HashMap<Integer, Set<Integer>>();

    public ConfluenceXMLPackage(InputSource source) throws IOException, WikiStreamException, XMLStreamException,
        FactoryConfigurationError, NumberFormatException, ConfigurationException
    {
        InputStream stream;

        if (source instanceof InputStreamInputSource) {
            stream = ((InputStreamInputSource) source).getInputStream();
        } else {
            throw new WikiStreamException(String.format("Unsupported input source of type [%s]", source.getClass()
                .getName()));
        }

        try {
            // Get temporary folder
            this.directory = File.createTempFile("confluencexml", "");
            this.directory.delete();
            this.directory.mkdir();
            this.directory.deleteOnExit();

            // Extract the zip
            ZipArchiveInputStream zais = new ZipArchiveInputStream(stream);
            for (ZipArchiveEntry zipEntry = zais.getNextZipEntry(); zipEntry != null; zipEntry = zais.getNextZipEntry()) {
                if (!zipEntry.isDirectory()) {
                    String path = zipEntry.getName();
                    File file = new File(this.directory, path);

                    if (path.equals("entities.xml")) {
                        this.entities = file;
                    } else if (path.equals("exportDescriptor.properties")) {
                        this.descriptor = file;
                    }

                    FileUtils.copyInputStreamToFile(new CloseShieldInputStream(zais), file);
                }
            }
        } finally {
            source.close();
        }

        // Initialize

        createTree();
    }

    public Date getDate(PropertiesConfiguration properties, String key) throws ParseException
    {
        String str = properties.getString(key);

        return str != null ? DATE_FORMAT.parse(str) : null;
    }

    public EntityReference getReferenceFromId(PropertiesConfiguration currentProperties, String key)
        throws ConfigurationException
    {
        int pageId = currentProperties.getInt(key);

        PropertiesConfiguration pageProperties = getPageProperties(pageId);

        int spaceId = pageProperties.getInt(KEY_PAGE_SPACE);
        int currentSpaceId = currentProperties.getInt(KEY_PAGE_SPACE);

        EntityReference spaceReference;
        if (spaceId != currentSpaceId) {
            spaceReference = new EntityReference(getSpaceName(currentSpaceId), EntityType.SPACE);
        } else {
            spaceReference = null;
        }

        return new EntityReference(pageProperties.getString(KEY_PAGE_TITLE), EntityType.DOCUMENT, spaceReference);
    }

    public String getSpaceName(int spaceId) throws ConfigurationException
    {
        PropertiesConfiguration spaceProperties = getSpaceProperties(spaceId);

        return spaceProperties.getString(KEY_SPACE_NAME);
    }

    public Map<Integer, Set<Integer>> getPages()
    {
        return this.pages;
    }

    private void createTree() throws XMLStreamException, FactoryConfigurationError, NumberFormatException, IOException,
        ConfigurationException, WikiStreamException
    {
        this.tree = new File(this.directory, "tree");
        this.tree.mkdir();

        InputStream stream = new FileInputStream(getEntities());

        XMLStreamReader xmlReader = XMLInputFactory.newInstance().createXMLStreamReader(stream);

        xmlReader.nextTag();

        for (xmlReader.nextTag(); xmlReader.isStartElement(); xmlReader.nextTag()) {
            String elementName = xmlReader.getLocalName();

            if (elementName.equals("object")) {
                readObject(xmlReader);
            } else {
                StAXUtils.skipElement(xmlReader);
            }
        }
    }

    private void readObject(XMLStreamReader xmlReader) throws XMLStreamException, NumberFormatException, IOException,
        ConfigurationException, WikiStreamException
    {
        String type = xmlReader.getAttributeValue(null, "class");

        if (type != null) {
            if (type.equals("Page")) {
                readPageObject(xmlReader);
            } else if (type.equals("Space")) {
                readSpaceObject(xmlReader);
            } else if (type.equals("BodyContent")) {
                readBodyContentObject(xmlReader);
            } else if (type.equals("SpaceDescription")) {
                readSpaceDescriptionObject(xmlReader);
            } else if (type.equals("SpacePermission")) {
                readSpacePermissionObject(xmlReader);
            } else if (type.equals("Attachment")) {
                readAttachmentObject(xmlReader);
            } else if (type.equals("ReferralLink")) {
                // TODO: any idea how to convert that ?
                StAXUtils.skipElement(xmlReader);
            } else if (type.equals("Label")) {
                // TODO: any idea how to convert that ?
                StAXUtils.skipElement(xmlReader);
            } else {
                StAXUtils.skipElement(xmlReader);
            }
        }
    }

    private int readObjectProperties(XMLStreamReader xmlReader, PropertiesConfiguration properties)
        throws XMLStreamException, WikiStreamException, ConfigurationException, IOException
    {
        int id = -1;

        for (xmlReader.nextTag(); xmlReader.isStartElement(); xmlReader.nextTag()) {
            String elementName = xmlReader.getLocalName();

            if (elementName.equals("id")) {
                id = Integer.valueOf(xmlReader.getElementText());
            } else if (elementName.equals("property")) {
                String propertyName = xmlReader.getAttributeValue(null, "name");

                properties.setProperty(propertyName, readProperty(xmlReader));
            } else if (elementName.equals("collection")) {
                String propertyName = xmlReader.getAttributeValue(null, "name");

                properties.setProperty(propertyName, readProperty(xmlReader));
            } else {
                StAXUtils.skipElement(xmlReader);
            }
        }

        return id;
    }

    private void readAttachmentObject(XMLStreamReader xmlReader) throws XMLStreamException, WikiStreamException,
        ConfigurationException, IOException
    {
        PropertiesConfiguration properties = new PropertiesConfiguration();

        int attachmentId = readObjectProperties(xmlReader, properties);

        int pageId = properties.getInt("content");

        // Save attachment
        saveAttachmentProperties(properties, pageId, attachmentId);
    }

    private void readSpaceObject(XMLStreamReader xmlReader) throws XMLStreamException, WikiStreamException,
        ConfigurationException, IOException
    {
        PropertiesConfiguration properties = new PropertiesConfiguration();

        int spaceId = readObjectProperties(xmlReader, properties);

        // Save page
        saveSpaceProperties(properties, spaceId);

        // Register space
        Set<Integer> spacePages = this.pages.get(spaceId);
        if (spacePages == null) {
            spacePages = new HashSet<Integer>();
            this.pages.put(spaceId, spacePages);
        }
    }

    private void readSpaceDescriptionObject(XMLStreamReader xmlReader) throws XMLStreamException, WikiStreamException,
        ConfigurationException, IOException
    {
        PropertiesConfiguration properties = new PropertiesConfiguration();

        int descriptionId = readObjectProperties(xmlReader, properties);

        // Save page
        savePageProperties(properties, descriptionId);
    }

    private void readSpacePermissionObject(XMLStreamReader xmlReader) throws XMLStreamException,
        ConfigurationException, IOException, WikiStreamException
    {
        PropertiesConfiguration properties = new PropertiesConfiguration();

        int permissionId = readObjectProperties(xmlReader, properties);

        int spaceId = properties.getInt("space");

        // Save attachment
        saveSpacePermissionsProperties(properties, spaceId, permissionId);
    }

    private void readBodyContentObject(XMLStreamReader xmlReader) throws XMLStreamException, ConfigurationException,
        WikiStreamException, IOException
    {
        PropertiesConfiguration properties = new PropertiesConfiguration();

        readObjectProperties(xmlReader, properties);

        int pageId = properties.getInt("content");

        savePageProperties(properties, pageId);
    }

    private void readPageObject(XMLStreamReader xmlReader) throws IOException, NumberFormatException,
        XMLStreamException, ConfigurationException, WikiStreamException
    {
        PropertiesConfiguration properties = new PropertiesConfiguration();

        int pageId = readObjectProperties(xmlReader, properties);

        // Save page
        savePageProperties(properties, pageId);

        // Register only current pages (they will take care of handling there history)
        Integer originalVersion = (Integer) properties.getProperty("originalVersion");
        if (originalVersion == null) {
            Integer spaceId = (Integer) properties.getInteger("space", null);
            Set<Integer> spacePages = this.pages.get(spaceId);
            if (spacePages == null) {
                spacePages = new HashSet<Integer>();
                this.pages.put(spaceId, spacePages);
            }
            spacePages.add(pageId);
        }
    }

    private Object readProperty(XMLStreamReader xmlReader) throws XMLStreamException, WikiStreamException
    {
        String propertyClass = xmlReader.getAttributeValue(null, "class");

        if (propertyClass == null) {
            return xmlReader.getElementText();
        } else if (propertyClass.equals("java.util.List") || propertyClass.equals("java.util.Collection")) {
            return readListProperty(xmlReader);
        } else if (propertyClass.equals("Page") || propertyClass.equals("Space") || propertyClass.equals("BodyContent")
            || propertyClass.equals("Attachment") || propertyClass.equals("SpaceDescription")
            || propertyClass.equals("Labelling") || propertyClass.equals("SpacePermission")) {
            return readIdProperty(xmlReader);
        } else {
            StAXUtils.skipElement(xmlReader);
        }

        return null;
    }

    private Object readIdProperty(XMLStreamReader xmlReader) throws WikiStreamException, XMLStreamException
    {
        xmlReader.nextTag();

        if (!xmlReader.getLocalName().equals("id")) {
            throw new WikiStreamException(String.format("Was expecting id element but found [%s]",
                xmlReader.getLocalName()));
        }

        Integer value = Integer.valueOf(xmlReader.getElementText());

        xmlReader.nextTag();

        return value;
    }

    private Object readListProperty(XMLStreamReader xmlReader) throws XMLStreamException, WikiStreamException
    {
        List<Object> list = new ArrayList<Object>();

        for (xmlReader.nextTag(); xmlReader.isStartElement(); xmlReader.nextTag()) {
            list.add(readProperty(xmlReader));
        }

        return list;
    }

    private File getSpacesFolder()
    {
        return new File(this.tree, "spaces");
    }

    private File getSpaceFolder(int spaceId)
    {
        return new File(getSpacesFolder(), String.valueOf(spaceId));
    }

    private File getPagesFolder()
    {
        return new File(this.tree, "pages");
    }

    private File getPageFolder(int pageId)
    {
        return new File(getPagesFolder(), String.valueOf(pageId));
    }

    private File getPagePropertiesFile(int pageId)
    {
        File folder = getPageFolder(pageId);

        return new File(folder, "properties.properties");
    }

    public List<Integer> getAttachments(int pageId)
    {
        File folder = getAttachmentsFolder(pageId);

        String[] attachmentFolders = folder.list();

        List<Integer> attachments = new ArrayList<Integer>(attachmentFolders.length);
        for (String attachmentIdString : attachmentFolders) {
            if (NumberUtils.isNumber(attachmentIdString)) {
                attachments.add(Integer.valueOf(attachmentIdString));
            }
        }

        return attachments;
    }

    private File getAttachmentsFolder(int pageId)
    {
        return new File(getPageFolder(pageId), "attachments");
    }

    private File getSpacePermissionsFolder(int spaceId)
    {
        return new File(getSpaceFolder(spaceId), "permissions");
    }

    private File getAttachmentFolder(int pageId, int attachmentId)
    {
        return new File(getAttachmentsFolder(pageId), String.valueOf(attachmentId));
    }

    private File getSpacePermissionFolder(int spaceId, int permissionId)
    {
        return new File(getSpacePermissionsFolder(spaceId), String.valueOf(permissionId));
    }

    private File getAttachmentPropertiesFile(int pageId, int attachmentId)
    {
        File folder = getAttachmentFolder(pageId, attachmentId);

        return new File(folder, "properties.properties");
    }

    private File getSpacePermissionPropertiesFile(int spaceId, int permissionId)
    {
        File folder = getSpacePermissionFolder(spaceId, permissionId);

        return new File(folder, "properties.properties");
    }

    private File getSpacePropertiesFile(int spaceId)
    {
        File folder = getSpaceFolder(spaceId);

        return new File(folder, "properties.properties");
    }

    public PropertiesConfiguration getPageProperties(int pageId) throws ConfigurationException
    {
        File file = getPagePropertiesFile(pageId);

        return new PropertiesConfiguration(file);
    }

    public PropertiesConfiguration getAttachmentProperties(int pageId, int attachmentId) throws ConfigurationException
    {
        File file = getAttachmentPropertiesFile(pageId, attachmentId);

        return new PropertiesConfiguration(file);
    }

    public PropertiesConfiguration getSpacePermissionProperties(int spaceId, int permissionId)
        throws ConfigurationException
    {
        File file = getSpacePermissionPropertiesFile(spaceId, permissionId);

        return new PropertiesConfiguration(file);
    }

    public PropertiesConfiguration getSpaceProperties(int spaceId) throws ConfigurationException
    {
        File file = getSpacePropertiesFile(spaceId);

        return new PropertiesConfiguration(file);
    }

    private void savePageProperties(PropertiesConfiguration properties, int pageId) throws IOException,
        ConfigurationException
    {
        PropertiesConfiguration fileProperties = getPageProperties(pageId);

        fileProperties.copy(properties);

        fileProperties.save();
    }

    private void saveAttachmentProperties(PropertiesConfiguration properties, int pageId, int attachmentId)
        throws IOException, ConfigurationException
    {
        PropertiesConfiguration fileProperties = getAttachmentProperties(pageId, attachmentId);

        fileProperties.copy(properties);

        fileProperties.save();
    }

    private void saveSpacePermissionsProperties(PropertiesConfiguration properties, int spaceId, int permissionId)
        throws IOException, ConfigurationException
    {
        PropertiesConfiguration fileProperties = getSpacePermissionProperties(spaceId, permissionId);

        fileProperties.copy(properties);

        fileProperties.save();
    }

    private void saveSpaceProperties(PropertiesConfiguration properties, int spaceId) throws IOException,
        ConfigurationException
    {
        PropertiesConfiguration fileProperties = getSpaceProperties(spaceId);

        fileProperties.copy(properties);

        fileProperties.save();
    }

    public File getEntities()
    {
        return this.entities;
    }

    public File getDescriptor()
    {
        return this.descriptor;
    }

    public File getAttachmentFile(int pageId, int attachmentId, int version)
    {
        File attachmentsFolder = new File(this.directory, "attachments");
        File attachmentsPageFolder = new File(attachmentsFolder, String.valueOf(pageId));
        File attachmentFolder = new File(attachmentsPageFolder, String.valueOf(attachmentId));

        return new File(attachmentFolder, String.valueOf(version));
    }

    public void close() throws IOException
    {
        if (this.tree != null) {
            FileUtils.deleteDirectory(this.tree);
        }
    }
}
