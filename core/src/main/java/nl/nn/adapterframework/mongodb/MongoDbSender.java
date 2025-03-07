/*
   Copyright 2021 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.nn.adapterframework.mongodb;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.naming.NamingException;

import org.apache.commons.lang3.StringUtils;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.Encoder;
import org.bson.codecs.EncoderContext;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.xml.sax.SAXException;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.connection.ServerDescription;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import lombok.Getter;
import lombok.Lombok;
import lombok.Setter;
import nl.nn.adapterframework.configuration.ConfigurationException;
import nl.nn.adapterframework.core.HasPhysicalDestination;
import nl.nn.adapterframework.core.IForwardTarget;
import nl.nn.adapterframework.core.ParameterException;
import nl.nn.adapterframework.core.PipeLineSession;
import nl.nn.adapterframework.core.PipeRunResult;
import nl.nn.adapterframework.core.SenderException;
import nl.nn.adapterframework.core.TimeoutException;
import nl.nn.adapterframework.jdbc.JdbcQuerySenderBase;
import nl.nn.adapterframework.parameters.ParameterValueList;
import nl.nn.adapterframework.stream.Message;
import nl.nn.adapterframework.stream.MessageOutputStream;
import nl.nn.adapterframework.stream.MessageOutputStreamCap;
import nl.nn.adapterframework.stream.StreamingException;
import nl.nn.adapterframework.stream.StreamingSenderBase;
import nl.nn.adapterframework.stream.document.ArrayBuilder;
import nl.nn.adapterframework.stream.document.DocumentBuilderFactory;
import nl.nn.adapterframework.stream.document.DocumentFormat;
import nl.nn.adapterframework.stream.document.IDocumentBuilder;
import nl.nn.adapterframework.stream.document.INodeBuilder;
import nl.nn.adapterframework.stream.document.ObjectBuilder;
import nl.nn.adapterframework.util.AppConstants;
import nl.nn.adapterframework.util.StringResolver;

/**
 * Sender to perform action on a MongoDB database.
 *
 * @ff.parameter database Database to connect to. Overrides attribute <code>database</code>
 * @ff.parameter collection Collection to act upon. Overrides attribute <code>collection</code>
 * @ff.parameter filter Filter. Can contain references to parameters between '?{' and '}'. Overrides attribute <code>filter</code>
 * @ff.parameter limit Limit to number of results returned. A value of 0 means 'no limit'. Overrides attribute <code>limit</code>
 *
 * @author Gerrit van Brakel
 *
 */
public class MongoDbSender extends StreamingSenderBase implements HasPhysicalDestination {

	private final @Getter(onMethod = @__(@Override)) String domain = "Mongo";
	public static final String PARAM_DATABASE="database";
	public static final String PARAM_COLLECTION="collection";
	public static final String PARAM_FILTER="filter";
	public static final String PARAM_LIMIT="limit";

	public static final String NAMED_PARAM_START=JdbcQuerySenderBase.UNP_START;
	public static final String NAMED_PARAM_END=JdbcQuerySenderBase.UNP_END;


	private @Getter String datasourceName;
	private @Getter String database;
	private @Getter String collection;
	private @Getter MongoAction action;
	private @Getter String filter;
	private @Getter int limit=0;
	private @Getter boolean countOnly=false;
	private @Getter DocumentFormat outputFormat=DocumentFormat.JSON;
	private @Getter boolean prettyPrint=false;

	private @Setter @Getter IMongoClientFactory mongoClientFactory = null; // Spring should wire this!

	private MongoClient mongoClient;
	private ConcurrentHashMap<String,MongoDatabase> mongoDatabases = new ConcurrentHashMap<>();

	public enum MongoAction {
		INSERTONE,
		INSERTMANY,
		FINDONE,
		FINDMANY,
		UPDATEONE,
		UPDATEMANY,
		DELETEONE,
		DELETEMANY;
	}


	@Override
	public void configure() throws ConfigurationException {
		super.configure();
		if (StringUtils.isEmpty(getDatasourceName())) {
			setDatasourceName(AppConstants.getInstance(getConfigurationClassLoader()).getString(JndiMongoClientFactory.DEFAULT_DATASOURCE_NAME_PROPERTY, JndiMongoClientFactory.GLOBAL_DEFAULT_DATASOURCE_NAME_DEFAULT));
		}
		if (mongoClientFactory==null) {
			throw new ConfigurationException("no mongoClientFactory available");
		}
		checkStringAttributeOrParameter("database", getDatabase(), PARAM_DATABASE);
		checkStringAttributeOrParameter("collection", getCollection(), PARAM_COLLECTION);
		if (getAction()==null) {
			throw new ConfigurationException("attribute action not specified");
		}
		if ((getLimit()>0 || (getParameterList()!=null && getParameterList().findParameter(PARAM_LIMIT)!=null)) && getAction()!=MongoAction.FINDMANY) {
			throw new ConfigurationException("attribute limit or parameter "+PARAM_LIMIT+" can only be used for action "+MongoAction.FINDMANY);
		}
	}

	@Override
	public void open() throws SenderException {
		try {
			mongoClient = mongoClientFactory.getMongoClient(getDatasourceName());
		} catch (NamingException e) {
			throw new SenderException("cannot open MongoDB datasource ["+getDatasourceName()+"]", e);
		}
		super.open();
	}

	@Override
	public void close() throws SenderException {
		try {
			super.close();
		} finally {
			if (mongoClient!=null) {
				mongoClient.close();
				mongoClient=null;
				mongoDatabases.clear();
			}
		}
	}

	@Override
	public MessageOutputStream provideOutputStream(PipeLineSession session, IForwardTarget next) throws StreamingException {
		return null;
	}


	@Override
	public PipeRunResult sendMessage(Message message, PipeLineSession session, IForwardTarget next) throws SenderException, TimeoutException {
		message.closeOnCloseOf(session, this);
		MongoAction mngaction = getAction();
		try (MessageOutputStream target = mngaction==MongoAction.FINDONE || mngaction==MongoAction.FINDMANY ? MessageOutputStream.getTargetStream(this, session, next) : new MessageOutputStreamCap(this, next)) {
			ParameterValueList pvl = ParameterValueList.get(getParameterList(), message, session);
			MongoDatabase mongoDatabase = getDatabase(pvl);
			MongoCollection<Document> mongoCollection = getCollection(mongoDatabase, pvl);
			switch (mngaction) {
			case INSERTONE:
				renderResult(mongoCollection.insertOne(getDocument(message)), target);
				break;
			case INSERTMANY:
				renderResult(mongoCollection.insertMany(getDocuments(message)), target);
				break;
			case FINDONE:
				renderResult(mongoCollection.find(getFilter(pvl, message)).first(), target);
				break;
			case FINDMANY:
				renderResult(mongoCollection.find(getFilter(pvl, message)).limit(getLimit(pvl)), target);
				break;
			case UPDATEONE:
				renderResult(mongoCollection.updateOne(getFilter(pvl, null), getDocument(message)), target);
				break;
			case UPDATEMANY:
				renderResult(mongoCollection.updateMany(getFilter(pvl, null), getDocument(message)), target);
				break;
			case DELETEONE:
				renderResult(mongoCollection.deleteOne(getFilter(pvl, message)), target);
				break;
			case DELETEMANY:
				renderResult(mongoCollection.deleteMany(getFilter(pvl, message)), target);
				break;
			default:
				throw new SenderException("Unknown action ["+getAction()+"]");
			}
			return target.getPipeRunResult();
		} catch (Exception e) {
			throw new SenderException("Cannot execute action ["+getAction()+"]", e);
		}
	}

	protected void renderResult(InsertOneResult insertOneResult, MessageOutputStream target) throws SAXException, StreamingException {
		try (ObjectBuilder builder = DocumentBuilderFactory.startObjectDocument(getOutputFormat(), "insertOneResult", target, isPrettyPrint())) {
			builder.add("acknowledged", insertOneResult.wasAcknowledged());
			if (insertOneResult.wasAcknowledged()) {
				builder.add("insertedId", renderField(insertOneResult.getInsertedId()));
			}
		}
	}

	protected void renderResult(InsertManyResult insertManyResult, MessageOutputStream target) throws SAXException, StreamingException {
		try (ObjectBuilder builder = DocumentBuilderFactory.startObjectDocument(getOutputFormat(), "insertManyResult", target, isPrettyPrint())) {
			builder.add("acknowledged", insertManyResult.wasAcknowledged());
			if (insertManyResult.wasAcknowledged()) {
				try (ObjectBuilder objectBuilder = builder.addObjectField("insertedIds")) {
					Map<Integer, BsonValue> insertedIds = insertManyResult.getInsertedIds();

					insertedIds.forEach((k,v)->{
						try {
							objectBuilder.add(Integer.toString(k), renderField(v));
						} catch (SAXException e) {
							throw Lombok.sneakyThrow(e);
						}
					});
				}
			}
		}
	}

	protected void renderResult(Document findResult, MessageOutputStream target) throws StreamingException {
		try (IDocumentBuilder builder = DocumentBuilderFactory.startDocument(getOutputFormat(), "FindOneResult", target, isPrettyPrint())) {
			JsonWriterSettings writerSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
			Encoder<Document> encoder = new DocumentCodec();
			JsonDocumentWriter jsonWriter = new JsonDocumentWriter(builder, writerSettings);
			encoder.encode(jsonWriter, findResult, EncoderContext.builder().build());
		} catch (Exception e) {
			throw new StreamingException("Could not render collection", e);
		}
	}

	protected void renderResult(FindIterable<Document> findResults, MessageOutputStream target) throws StreamingException {
		try {
			if (isCountOnly()) {
				try (Writer writer = target.asWriter()) {
					int count=0;
					for (Document doc : findResults) {
						count++;
					}
					writer.write(Integer.toString(count));
				}
				return;
			}
			try (ArrayBuilder builder = DocumentBuilderFactory.startArrayDocument(getOutputFormat(), "FindManyResult", "item", target, isPrettyPrint())) {
				JsonWriterSettings writerSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
				Encoder<Document> encoder = new DocumentCodec();
				for (Document doc : findResults) {
					try (INodeBuilder element = builder.addElement()) {
						JsonDocumentWriter jsonWriter = new JsonDocumentWriter(element, writerSettings);
						encoder.encode(jsonWriter, doc, EncoderContext.builder().build());
					}
				}
			}
		} catch (Exception e) {
			throw new StreamingException("Could not render collection", e);
		}
	}

	protected void renderResult(UpdateResult updateResult, MessageOutputStream target) throws SAXException, StreamingException {
		try (ObjectBuilder builder = DocumentBuilderFactory.startObjectDocument(getOutputFormat(), "updateResult", target, isPrettyPrint())) {
			builder.add("acknowledged", updateResult.wasAcknowledged());
			if (updateResult.wasAcknowledged()) {
				builder.add("matchedCount", updateResult.getMatchedCount());
				builder.add("modifiedCount", updateResult.getModifiedCount());
				addOptionalValue(builder, "upsertedId", updateResult.getUpsertedId());
			}
		}
	}

	protected void renderResult(DeleteResult deleteResult, MessageOutputStream target) throws SAXException, StreamingException {
		try (ObjectBuilder builder = DocumentBuilderFactory.startObjectDocument(getOutputFormat(), "deleteResult", target, isPrettyPrint())) {
			builder.add("acknowledged", deleteResult.wasAcknowledged());
			if (deleteResult.wasAcknowledged()) {
				builder.add("deleteCount", deleteResult.getDeletedCount());
			}
		}
	}

	private String renderField(BsonValue bsonValue) {
		if (bsonValue.isObjectId()) {
			return bsonValue.asObjectId().getValue().toString();
		}
		if (bsonValue.isString()) {
			return bsonValue.asString().getValue().toString();
		}
		return bsonValue.toString();
	}

	protected void addOptionalValue(ObjectBuilder builder, String name, BsonValue bsonValue) throws SAXException {
		if (bsonValue!=null) {
			builder.add(name, bsonValue.isObjectId()? bsonValue.asObjectId().getValue().toString() : bsonValue.asString().getValue());
		}
	}


	protected Document getDocument(Message message) throws IOException {
		return getDocument(message.asString());
	}

	protected Document getDocument(String message) {
		return Document.parse(message);
	}

	protected List<Document> getDocuments(Message message) throws IOException {
		JsonArray array = Json.createReader(message.asReader()).readArray();
		List<Document> documents = new ArrayList<>();
		for (JsonObject object:array.getValuesAs(JsonObject.class)) {
			documents.add(getDocument(object.toString()));
		}
		return documents;
	}

	protected MongoDatabase getDatabase(ParameterValueList pvl) throws SenderException {
		String databaseName = getParameterOverriddenAttributeValue(pvl, PARAM_DATABASE, getDatabase());
		if (StringUtils.isEmpty(databaseName)) {
			throw new SenderException("no database found from attribute or parameter");
		}
		return mongoDatabases.computeIfAbsent(databaseName, (d)->mongoClient.getDatabase(databaseName));
	}

	protected MongoCollection<Document> getCollection(MongoDatabase mongoDatabase, ParameterValueList pvl) throws SenderException {
		String collectionName = getParameterOverriddenAttributeValue(pvl, PARAM_COLLECTION, getCollection());
		if (StringUtils.isEmpty(collectionName)) {
			throw new SenderException("no collection found from attribute or parameter");
		}
		return mongoDatabase.getCollection(collectionName);
	}

	protected Document getFilter(ParameterValueList pvl, Message message) throws IOException, ParameterException, IllegalArgumentException {
		String filterSpec = getParameterOverriddenAttributeValue(pvl, PARAM_FILTER, getFilter());
		if (StringUtils.isEmpty(filterSpec)) {
			filterSpec = Message.isEmpty(message) ? "" : message.asString();
		}
		if (filterSpec.contains(NAMED_PARAM_START) && filterSpec.contains(NAMED_PARAM_END)) {
			filterSpec = StringResolver.substVars(filterSpec, pvl.getValueMap(), null, null, NAMED_PARAM_START, NAMED_PARAM_END);
		}
		return getDocument(filterSpec);
	}

	protected int getLimit(ParameterValueList pvl) {
		return getParameterOverriddenAttributeValue(pvl, PARAM_LIMIT, getLimit());
	}

	@Override
	public String getPhysicalDestinationName() {
		String result = "datasource ["+getDatasourceName()+"]";
		if (mongoClient!=null) {
			List<ServerDescription> serverDescriptions = mongoClient.getClusterDescription().getServerDescriptions();
			if (!serverDescriptions.isEmpty()) {
				result += " server ["+serverDescriptions.get(0).getAddress()+"]";
			}
		}
		return result;
	}


	/**
	 * The MongoDB datasource
	 * @ff.default {@value JndiMongoClientFactory#DEFAULT_DATASOURCE_NAME_PROPERTY}
	 */
	public void setDatasourceName(String datasourceName) {
		this.datasourceName = datasourceName;
	}

	/** Database to connect to. Can be overridden by parameter {@value #PARAM_DATABASE} */
	public void setDatabase(String database) {
		this.database = database;
	}

	/** Collection to act upon. Can be overridden by parameter {@value #PARAM_COLLECTION} */
	public void setCollection(String collection) {
		this.collection = collection;
	}

	/** Action */
	public void setAction(MongoAction action) {
		this.action = action;
	}

	/** Filter. Can contain references to parameters between {@value #NAMED_PARAM_START} and {@value #NAMED_PARAM_END}. Can be overridden by parameter {@value #PARAM_FILTER} */
	public void setFilter(String filter) {
		this.filter = filter;
	}

	/**
	 * Limit to number of results returned. A value of 0 means 'no limit'. Can be overridden by parameter {@value #PARAM_LIMIT}.
	 * @ff.default 0
	 */
	public void setLimit(int limit) {
		this.limit = limit;
	}

	/**
	 * Only for find operation: return only the count and not the full document(s)
	 * @ff.default false
	 */
	public void setCountOnly(boolean countOnly) {
		this.countOnly = countOnly;
	}

	/**
	 * OutputFormat
	 * @ff.default JSON
	 */
	public void setOutputFormat(DocumentFormat outputFormat) {
		this.outputFormat = outputFormat;
	}

	/** Format the output in easy legible way (currently only for XML) */
	public void setPrettyPrint(boolean prettyPrint) {
		this.prettyPrint = prettyPrint;
	}

}
