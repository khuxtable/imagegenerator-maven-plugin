/*
 * Copyright (c) 2010 Kathryn Huxtable.
 *
 * This file is part of the Image Generator Maven plugin.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * $Id$
 */
package org.kathrynhuxtable.maven.plugins.imageGenerator;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Goal creates a directory of images from Swing using a specified XML file.
 *
 * @description                  Create a directory of images from Swing using a
 *                               specified XML file.
 * @goal                         generate
 * @phase                        pre-site
 * @requiresDependencyResolution runtime
 * @configurator                 include-project-dependencies
 */
public class ImageGeneratorMojo extends AbstractMojo {

    /**
     * Location of the configuration file.
     *
     * @parameter expression="${imagegenerator.configFile}"
     *            default-value="${basedir}/src/site/image-generator.xml"
     */
    private File configFile;

    /**
     * Name of the look and feel class.
     *
     * @parameter expression="${imagegenerator.lookAndFeel}"
     * @required
     */
    private String lookAndFeel;

    /**
     * Location of the output directory.
     *
     * @parameter expression="${imagegenerator.outputDirectory}"
     *            default-value="${project.build.directory}/generated-site/resources/images"
     */
    private File outputDirectory;

    /**
     * Location of the saved configuration file.
     *
     * @parameter expression="${imagegenerator.savedConfigFile}"
     *            default-value="${project.build.directory}/generated-site/image-generator.xml"
     */
    private File savedConfigFile;

    /** A JPanel used for embedding the images. This is reused by each image. */
    private JPanel panel;

    /**
     * Set the config file.
     *
     * @param configFile the config file.
     */
    public void setConfigFile(File configFile) {
        this.configFile = configFile;
    }

    /**
     * Set the look and feel.
     *
     * @param lookAndFeel the look and feel class name.
     */
    public void setLookAndFeel(String lookAndFeel) {
        this.lookAndFeel = lookAndFeel;
    }

    /**
     * Set the output directory.
     *
     * @param outputDirectory the output directory.
     */
    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    /**
     * Set the saved config file.
     *
     * @param savedConfigFile the saved config file.
     */
    public void setSavedConfigFile(File savedConfigFile) {
        this.savedConfigFile = savedConfigFile;
    }

    /**
     * @see org.apache.maven.plugin.AbstractMojo#execute()
     */
    public void execute() throws MojoExecutionException {
        Map<String, ImageInfo> config    = parseConfigFile(configFile, true);
        Map<String, ImageInfo> oldConfig = parseConfigFile(savedConfigFile, false);

        createOutputDirectoryIfNecessary();

        try {
            UIManager.setLookAndFeel(lookAndFeel);
        } catch (Exception e) {
            e.printStackTrace();
            throw new MojoExecutionException("Unable to set look and feel " + lookAndFeel, e);
        }

        panel = new JPanel();
        panel.setOpaque(true);

        generateImageFiles(config, oldConfig);

        copyConfigToOldConfig(configFile, savedConfigFile);
    }

    /**
     * Generate the image files, skipping any that are identical to a filename
     * in the saved config file.
     *
     * @param  config    the current config file.
     * @param  oldConfig the saved config file, for comparison.
     *
     * @throws MojoExecutionException if an error occurs.
     */
    private void generateImageFiles(Map<String, ImageInfo> config, Map<String, ImageInfo> oldConfig) throws MojoExecutionException {
        for (String filename : config.keySet()) {
            ImageInfo info    = config.get(filename);
            ImageInfo oldInfo = oldConfig.get(filename);

            if (oldInfo == null || !info.equals(oldInfo)) {
                getLog().info("Creating image file " + filename);
                drawImage(filename, info.className, info.width, info.height, info.panelWidth, info.panelHeight, info.args,
                          info.properties);
            }
        }
    }

    /**
     * Create an image from the info and write it to a file.
     *
     * @param  filename    the filename for the image.
     * @param  className   the class of control to be created, e.g.
     *                     "javax.swing.JButton".
     * @param  width       the desired width of the control.
     * @param  height      the desired height of the control.
     * @param  panelWidth  the desired width of the panel it is embedded in.
     * @param  panelHeight the desired height of the panel it is embedded in.
     * @param  args        any arguments to the control constructor.
     * @param  properties  a map containing the client properties to set on the
     *                     control.
     *
     * @throws MojoExecutionException if an error occurs.
     */
    private void drawImage(String filename, String className, int width, int height, int panelWidth, int panelHeight, Object[] args,
            Map<String, Object> properties) throws MojoExecutionException {
        // Create the Swing object.
        JComponent c = createSwingObject(className, args);

        // Set its properties.
        for (String key : properties.keySet()) {
            c.putClientProperty(key, properties.get(key));
        }

        // Paint to a buffered image.
        BufferedImage image = paintToBufferedImage(c, width, height, panelWidth, panelHeight);

        // Write the file.
        writeImageFile(filename, image);
    }

    /**
     * Create a Swing object from its class name and arguments.
     *
     * @param  className the class name.
     * @param  args      the arguments. May be empty.
     *
     * @return the newly created Swing object.
     *
     * @throws MojoExecutionException if the Swing object cannot be created.
     */
    private JComponent createSwingObject(String className, Object... args) throws MojoExecutionException {
        try {
            Class<?> c = Class.forName(className);

            Class<?>[] argClasses = new Class[args.length];

            for (int i = 0; i < args.length; i++) {
                argClasses[i] = args[i].getClass();
            }

            Constructor<?> constructor = c.getConstructor(argClasses);

            if (constructor == null) {
                throw new MojoExecutionException("Failed to find the constructor for the class: " + className);
            }

            return (JComponent) constructor.newInstance(args);
        } catch (Exception e) {
            throw new MojoExecutionException("Unable to create the object " + className + "(" + args + ")", e);
        }
    }

    /**
     * Paint the control to a newly created buffered image.
     *
     * @param  c           the control to paint.
     * @param  width       the desired width of the control.
     * @param  height      the desired height of the control.
     * @param  panelWidth  the desired width of the panel it is embedded in.
     * @param  panelHeight the desired height of the panel it is embedded in.
     *
     * @return the buffered image containing the printed control against a panel
     *         background.
     */
    private BufferedImage paintToBufferedImage(JComponent c, int width, int height, int panelWidth, int panelHeight) {
        panel.removeAll();
        panel.setSize(panelWidth, panelHeight);

        panel.add(c);
        c.setBounds((panelWidth - width) / 2, (panelHeight - height) / 2, width, height);

        BufferedImage image = new BufferedImage(panelWidth, panelHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics      g     = image.createGraphics();

        panel.paint(g);
        return image;
    }

    /**
     * Write the buffered image to the file.
     *
     * @param  filename the filename portion of the file. The directory and the
     *                  extension will be added.
     * @param  image    the buffered image.
     *
     * @throws MojoExecutionException if unable to write the file.
     */
    private void writeImageFile(String filename, BufferedImage image) throws MojoExecutionException {
        File outputfile = new File(outputDirectory, filename + ".png");

        try {
            ImageIO.write(image, "png", outputfile);
        } catch (IOException e) {
            throw new MojoExecutionException("Error writing image file " + outputfile, e);
        }
    }

    /**
     * Parse an XML image config file into a hash of ImageInfo objects.
     *
     * @param  filename    the XML config file.
     * @param  quitOnError {@code true} causes an exception to be thrown if an
     *                     error occurs, {@code false} ignores the error and
     *                     returns the list as it currently is.
     *
     * @return a map of ImageInfo objects, indexed by image filename.
     *
     * @throws MojoExecutionException if an error occurs and {@code quitOnError}
     *                                is {@code true}.
     */
    private Map<String, ImageInfo> parseConfigFile(File filename, boolean quitOnError) throws MojoExecutionException {
        Map<String, ImageInfo> list         = new HashMap<String, ImageInfo>();
        InputStream            configStream = openInputStream(filename, quitOnError);
        Document               doc          = null;

        if (configStream == null) {
            // This only happens if quitOnError is false and the stream couldn't be opened.
            return list;
        }

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder        db  = dbf.newDocumentBuilder();

            doc = db.parse(configStream);
        } catch (Exception e) {
            closeInputStream(configStream);
            if (quitOnError) {
                throw new MojoExecutionException("Unable to parse XML config file " + filename, e);
            }

            return list;
        }

        doc.getDocumentElement().normalize();
        NodeList nodeList = doc.getElementsByTagName("image");

        for (int i = 0; i < nodeList.getLength(); i++) {
            Node imageNode = nodeList.item(i);

            if (imageNode.getNodeType() == Node.ELEMENT_NODE) {
                ImageInfo info      = new ImageInfo();
                String    imageFile = getImageInfo((Element) imageNode, info);

                list.put(imageFile, info);
            }
        }

        closeInputStream(configStream);

        return list;
    }

    /**
     * Open an input stream from the filename.
     *
     * @param  filename    the file to create a stream from.
     * @param  quitOnError {@code true} causes an exception to be thrown if an
     *                     error occurs, {@code false} ignores the error and
     *                     returns {@code null}.
     *
     * @return the input stream.
     *
     * @throws MojoExecutionException if an error occurs and {@code quitOnError}
     *                                is {@code true}.
     */
    private InputStream openInputStream(File filename, boolean quitOnError) throws MojoExecutionException {
        try {
            return new FileInputStream(filename);
        } catch (FileNotFoundException e) {
            if (quitOnError) {
                throw new MojoExecutionException("Unable to open file \"" + filename, e);
            }

            return null;
        }
    }

    /**
     * Close the input stream.
     *
     * @param stream the stream to close
     */
    private void closeInputStream(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // Do nothing.
        }
    }

    /**
     * Get the File for the output directory, creating it if necessary.
     *
     * @throws MojoExecutionException if for some reason the directory cannot be
     *                                used, e.g. it is not a directory, or is
     *                                not writable.
     */
    private void createOutputDirectoryIfNecessary() throws MojoExecutionException {
        if (outputDirectory.exists()) {
            if (!outputDirectory.isDirectory()) {
                throw new MojoExecutionException("Output directory \"" + outputDirectory + "\" exists, but is not a directory.");
            } else if (!outputDirectory.canWrite()) {
                throw new MojoExecutionException("Output directory \"" + outputDirectory + "\" exists, but is not writable.");
            }
        } else if (!outputDirectory.mkdirs()) {
            throw new MojoExecutionException("Output directory \"" + outputDirectory + "\" could not be created.");
        }
    }

    /**
     * Copy the current config file to the saved config file.
     *
     * @param  configFilename    the current config file name.
     * @param  oldConfigFilename the saved config file name.
     *
     * @throws MojoExecutionException if an error occurs.
     */
    private void copyConfigToOldConfig(File configFilename, File oldConfigFilename) throws MojoExecutionException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(openInputStream(configFilename, true)));

        BufferedWriter writer = null;

        try {
            writer = new BufferedWriter(new FileWriter(oldConfigFilename));
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to open for writing the file " + oldConfigFilename, e);
        }

        try {
            for (String line = null; (line = reader.readLine()) != null;) {
                writer.write(line);
                writer.write("\n");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to write " + oldConfigFilename, e);
        } finally {
            try {
                writer.close();
            } catch (IOException e) {
                // Do nothing.
            }
        }
    }

    /**
     * Parse the XML for an image element to create the ImageInfo class for it.
     *
     * @param  imageElem the W3C Element containing the image information.
     * @param  info      the ImageInfo object to fill with data from XML.
     *
     * @return the filename to write the image into.
     *
     * @throws MojoExecutionException if an error occurs.
     */
    private String getImageInfo(Element imageElem, ImageInfo info) throws MojoExecutionException {
        String filename = imageElem.getAttribute("file");

        String w  = imageElem.getAttribute("width");
        String h  = imageElem.getAttribute("height");
        String pw = imageElem.getAttribute("panelWidth");
        String ph = imageElem.getAttribute("panelHeight");

        if (pw.length() == 0) {
            pw = w;
        }

        if (ph.length() == 0) {
            ph = h;
        }

        info.className   = imageElem.getAttribute("class");
        info.width       = Integer.parseInt(w);
        info.height      = Integer.parseInt(h);
        info.panelWidth  = Integer.parseInt(pw);
        info.panelHeight = Integer.parseInt(ph);

        List<Object> argList = new ArrayList<Object>();

        NodeList nodeList = imageElem.getElementsByTagName("argument");

        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);

            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element argElem = (Element) node;
                String  type    = argElem.getAttribute("type");
                String  value   = argElem.getAttribute("value");

                argList.add(parseObject(type, value));
            }
        }

        info.args = argList.toArray();

        info.properties = new HashMap<String, Object>();

        nodeList = imageElem.getElementsByTagName("clientProperty");

        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);

            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element argElem = (Element) node;
                String  name    = argElem.getAttribute("name");
                String  type    = argElem.getAttribute("type");
                String  value   = argElem.getAttribute("value");

                info.properties.put(name, parseObject(type, value));
            }
        }

        info.args = argList.toArray();

        return filename;
    }

    /**
     * Parse the value as an Object, given its type and value.
     *
     * @param  type  the type, e.g. String, Integer, Float, or Double.
     * @param  value the String value.
     *
     * @return the value as an Object of the specified type.
     *
     * @throws MojoExecutionException if the type is not recognized.
     */
    private Object parseObject(String type, String value) throws MojoExecutionException {
        Object obj = null;

        if ("String".equals(type)) {
            obj = value;
        } else if ("Integer".equals(type)) {
            obj = Integer.parseInt(value);
        } else if ("Float".equals(type)) {
            obj = Float.parseFloat(value);
        } else if ("Double".equals(type)) {
            obj = Double.parseDouble(value);
        } else {
            throw new MojoExecutionException("Unknown argument type: " + type);
        }

        return obj;
    }

    /**
     * Information used to generate each image.
     */
    public static class ImageInfo {
        String              className;
        int                 width;
        int                 height;
        int                 panelWidth;
        int                 panelHeight;
        Object[]            args;
        Map<String, Object> properties;

        /**
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ImageInfo)) {
                return false;
            }

            ImageInfo other = (ImageInfo) obj;

            return (className.equals(other.className) && width == other.width && height == other.height && panelWidth == other.panelWidth
                        && panelHeight == other.panelHeight && argsEquals(other) && properties.equals(other.properties));
        }

        /**
         * Compare args objects for equality.
         *
         * @param  other the other ImageInfo object.
         *
         * @return {@code true} if the two args have the same number and each
         *         element is equal, {@code false} otherwise.
         */
        private boolean argsEquals(ImageInfo other) {
            if (args.length != other.args.length) {
                return false;
            }

            for (int i = 0; i < args.length; i++) {
                if (!args[i].equals(other.args[i])) {
                    return false;
                }
            }

            return true;
        }
    }
}
