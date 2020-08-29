package net.jimblackler.jsonschemafriend;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.json.JSONObject;

public class SchemaStore {
  private static final URI ROOT = URI.create("");

  private static final Logger LOG = Logger.getLogger(SchemaStore.class.getName());
  private final Map<URI, Schema> builtSchemas = new HashMap<>();
  private final IdRefMap idRefMap = new IdRefMap();
  private final DocumentSource documentSource;
  private int memorySchemaNumber;

  public SchemaStore() {
    documentSource = new DocumentSource();
  }

  public SchemaStore(DocumentSource documentSource) {
    this.documentSource = documentSource;
  }

  public Schema loadSchema(JSONObject jsonObject) throws GenerationException {
    // Every document needs a URI. We generate a nice short one. If the client needs it, it can be
    // obtained via schema.getUri();
    URI uri = URI.create("ram://" + memorySchemaNumber);
    memorySchemaNumber++;
    documentSource.store(uri, jsonObject);
    return loadSchema(uri);
  }

  public Schema loadSchema(File file) throws GenerationException {
    return loadSchema(file.toURI());
  }

  public Schema loadSchema(URL url) throws GenerationException {
    try {
      return loadSchema(url.toURI());
    } catch (URISyntaxException e) {
      throw new GenerationException(e);
    }
  }

  public Schema loadSchema(URI uri) throws GenerationException {
    return loadSchema(uri, URI.create("http://json-schema.org/draft-07/schema#"));
  }

  public Schema loadSchema(URI uri, URI defaultMetaSchema) throws GenerationException {
    Object document = documentSource.fetchDocument(PathUtils.baseDocumentFromUri(uri));
    Object schemaObject = PathUtils.fetchFromPath(document, uri.getRawFragment());
    // If we can, we fetch and build the schema's meta-schema and validate the object against it,
    // before we attempt to build the Schema.
    // This can't be done inside the Schema builder because the schema's meta-schema might be in its
    // own graph, so the meta-schema won't be built in full when it's first available.
    if (schemaObject instanceof JSONObject) {
      String schemaValue = ((JSONObject) schemaObject).optString("$schema");
      URI metaSchemaUri = schemaValue.isEmpty() ? defaultMetaSchema : URI.create(schemaValue);
      Schema metaSchema = getSchema(metaSchemaUri, metaSchemaUri);
      List<ValidationError> errors = new ArrayList<>();
      metaSchema.validate(schemaObject, ROOT, errors::add);
      if (!errors.isEmpty()) {
        throw new GenerationException(errors.stream()
                                          .map(Object::toString)
                                          .collect(Collectors.joining(System.lineSeparator())));
      }
    }

    return getSchema(uri, defaultMetaSchema);
  }

  Schema getSchema(URI uri, URI defaultMetaSchema) throws GenerationException {
    // The schema at an uri can be requested by a client that doesn't know that the uri is the
    // beginning of a chain of $refs. We must find the end of the chain, where the actual schema is
    // to be found.
    uri = idRefMap.finalLocation(uri, documentSource, defaultMetaSchema);

    // Now we have the final path we can see if we've already built it.
    if (builtSchemas.containsKey(uri)) {
      return builtSchemas.get(uri);
    }

    Object baseDocument = documentSource.fetchDocument(PathUtils.baseDocumentFromUri(uri));
    Object object = PathUtils.fetchFromPath(baseDocument, uri.getRawFragment());

    if (object instanceof Boolean) {
      return new BooleanSchema(this, uri, (boolean) object);
    }
    if (object instanceof JSONObject) {
      return new ObjectSchema(this, uri, defaultMetaSchema);
    }
    LOG.warning("No schema at " + uri);
    return null;
  }

  public void register(URI path, Schema schema) throws GenerationException {
    if (builtSchemas.put(path, schema) != null) {
      throw new GenerationException(path + " already registered");
    }
  }

  public DocumentSource getDocumentSource() {
    // TOOO: consider how we can avoid vending a dependency directly like this.
    return documentSource;
  }
}