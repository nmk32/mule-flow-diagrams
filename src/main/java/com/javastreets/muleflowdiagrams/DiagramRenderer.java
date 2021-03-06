package com.javastreets.muleflowdiagrams;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javastreets.muleflowdiagrams.app.CommandModel;
import com.javastreets.muleflowdiagrams.drawings.Diagram;
import com.javastreets.muleflowdiagrams.drawings.DrawingContext;
import com.javastreets.muleflowdiagrams.model.ComponentItem;
import com.javastreets.muleflowdiagrams.model.FlowContainer;

public class DiagramRenderer {
  public static final int MULE_VERSION_4 = 4;
  public static final int MULE_VERSION_3 = 3;
  Logger log = LoggerFactory.getLogger(DiagramRenderer.class);

  private CommandModel commandModel;

  public DiagramRenderer(CommandModel commandModel) {
    this.commandModel = commandModel;
  }

  Map<String, ComponentItem> prepareKnownComponents() {
    Map<String, ComponentItem> items = new HashMap<>();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(Thread.currentThread()
        .getContextClassLoader().getResourceAsStream("mule-components.csv")))) {
      for (String line; (line = br.readLine()) != null;) {
        if (!line.startsWith("prefix")) {
          log.debug("Reading component line - {}", line);
          String[] part = line.split(",");
          if (part.length != 6) {
            log.error(
                "Found an invalid configuration line in mule components file. Column count must be 5. Line - {}",
                line);
            throw new RuntimeException("Invalid mule components configuration file.");
          }
          ComponentItem item = new ComponentItem();
          item.setPrefix(part[0]);
          item.setOperation(part[1]);
          item.setSource(Boolean.valueOf(part[2]));
          if (item.getOperation().equals("*") && item.isSource()) {
            log.error(
                "Wildcard operation entry as a source is not allowed. Please create a separate entry for source if needed. Line - {}",
                line);
            throw new RuntimeException("Invalid mule components configuration file.");
          }
          item.setPathAttributeName(part[3]);
          item.setConfigAttributeName(part[4]);
          item.setAsync(Boolean.valueOf(part[5]));
          items.putIfAbsent(item.qualifiedName(), item);
        }
      }
      // line is not visible here.
    } catch (IOException e) {
      log.error("mule-components file not found", e);
    }
    return items;
  }

  public Boolean render() {
    try {
      List<FlowContainer> flows = findFlows();
      return diagram(flows);
    } catch (IOException e) {
      log.error("Error while parsing xml file", e);
      return false;
    }
  }

  boolean existInSource(String path) {
    return Files.exists(Paths.get(commandModel.getSourcePath().toString(), path));
  }

  List<FlowContainer> findFlows() throws IOException {
    Path newSourcePath = getMuleSourcePath();
    List<FlowContainer> flows = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(newSourcePath)) {
      List<Path> xmls = paths
          .filter(
              path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".xml"))
          .collect(Collectors.toList());
      Map<String, ComponentItem> knownComponents = prepareKnownComponents();
      for (Path path : xmls) {
        flows(flows, knownComponents, path);
      }
    }
    return flows;
  }

  Path getMuleSourcePath() {
    Path newSourcePath = commandModel.getSourcePath();
    commandModel.setMuleVersion(MULE_VERSION_4);
    if (Files.isDirectory(commandModel.getSourcePath())) {
      log.debug("Source is a directory {}", commandModel.getSourcePath());
      if (existInSource("src/main/mule/") && existInSource("mule-artifact.json")) {
        log.info(
            "Found standard Mule 4 source structure 'src/main/mule'. Source is a Mule-4 project.");
        newSourcePath = Paths.get(commandModel.getSourcePath().toString(), "src/main/mule");
        commandModel.setMuleVersion(MULE_VERSION_4);
      } else if (existInSource("src/main/app/") && existInSource("mule-project.xml")) {
        log.info(
            "Found standard Mule 3 source structure 'src/main/app'. Source is a Mule-3 project.");
        newSourcePath = Paths.get(commandModel.getSourcePath().toString(), "src/main/app");
        commandModel.setMuleVersion(MULE_VERSION_3);
      } else {
        log.warn(
            "No known standard Mule (3/4) directory structure found (src/main/mule or src/main/app).");
      }
      log.info(
          "Source directory '{}' will be scanned recursively to find Mule {} configuration files.",
          newSourcePath, commandModel.getMuleVersion());
    } else {
      log.info("Reading source file {}", newSourcePath);
    }
    return newSourcePath;
  }

  void flows(List<FlowContainer> flows, Map<String, ComponentItem> knownComponents, Path path) {
    log.debug("Reading file {}", path);
    MuleXmlParser muleXmlParser = new MuleXmlParser(path.toAbsolutePath().toString());
    muleXmlParser.parse();
    if (muleXmlParser.isMuleFile()) {
      flows.addAll(muleXmlParser.getMuleFlows(knownComponents));
    } else {
      log.debug("Not a mule configuration file: {}", path);
    }
  }

  Boolean diagram(List<FlowContainer> flows) {
    if (flows.isEmpty()) {
      log.warn("No mule flows found for creating diagram.");
      return false;
    }
    DrawingContext context = drawingContext(commandModel);
    context.setComponents(Collections.unmodifiableList(flows));
    context.setKnownComponents(prepareKnownComponents());
    ServiceLoader<Diagram> diagramServices = ServiceLoader.load(Diagram.class);
    Iterator<Diagram> its = diagramServices.iterator();
    boolean drawn = false;
    while (its.hasNext()) {
      Diagram diagram = its.next();
      log.debug("Analyzing diagram provider {}", diagram.getClass());
      if (diagram.supports(commandModel.getDiagramType())) {
        log.debug("Found a supporting provider {} for drawing {}", diagram.getClass(),
            commandModel.getDiagramType());
        log.info("Initiating drawing {} at {}", diagram.name(), commandModel.getTargetPath());
        if (context.getFlowName() != null) {
          log.info("Generating diagram for dependencies of single flow only - {}",
              context.getFlowName());
        }
        drawn = diagram.draw(context);
        log.info("Generated {} at {}", diagram.name(), context.getOutputFile().getAbsolutePath());
        break;
      }
    }
    return drawn;
  }

  public DrawingContext drawingContext(CommandModel model) {
    DrawingContext context = new DrawingContext();
    context.setDiagramType(model.getDiagramType());
    context.setOutputFile(new File(model.getTargetPath().toFile(), model.getOutputFilename()));
    context.setFlowName(model.getFlowName());
    return context;
  }
}
