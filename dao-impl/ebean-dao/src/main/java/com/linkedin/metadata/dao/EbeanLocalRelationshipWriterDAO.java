package com.linkedin.metadata.dao;

import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.metadata.dao.builder.BaseLocalRelationshipBuilder.LocalRelationshipUpdates;
import com.linkedin.metadata.dao.internal.BaseGraphWriterDAO;
import com.linkedin.metadata.dao.utils.GraphUtils;
import com.linkedin.metadata.dao.utils.RecordUtils;
import com.linkedin.metadata.dao.utils.SQLSchemaUtils;
import com.linkedin.metadata.dao.utils.SQLStatementUtils;
import com.linkedin.metadata.validator.RelationshipValidator;
import io.ebean.EbeanServer;
import io.ebean.SqlUpdate;
import io.ebean.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.linkedin.metadata.dao.utils.ModelUtils.*;

public class EbeanLocalRelationshipWriterDAO extends BaseGraphWriterDAO {
  private static final String DEFAULT_ACTOR = "urn:li:principal:UNKNOWN";
  private final EbeanServer _server;

  // Common column names shared by all local relationship tables.
  private static class CommonColumnName {
    private static final String SOURCE = "source";
    private static final String DESTINATION = "destination";
    private static final String SOURCE_TYPE = "source_type";
    private static final String DESTINATION_TYPE = "destination_type";
    private static final String METADATA = "metadata";
    private static final String LAST_MODIFIED_ON = "lastmodifiedon";
    private static final String LAST_MODIFIED_BY = "lastmodifiedby";
  }

  public EbeanLocalRelationshipWriterDAO(EbeanServer server) {
    _server = server;
  }

  /**
   * Process the local relationship updates with transaction guarantee.
   * @param urn Urn of the entity to update relationships.
   * @param relationshipUpdates Updates to local relationship tables.
   * @param isTestMode whether to use test schema
   */
  @Transactional
  public void processLocalRelationshipUpdates(@Nonnull Urn urn,
      @Nonnull List<LocalRelationshipUpdates> relationshipUpdates, boolean isTestMode) {
    for (LocalRelationshipUpdates relationshipUpdate : relationshipUpdates) {
      if (relationshipUpdate.getRelationships().isEmpty()) {
        clearRelationshipsByEntity(urn, relationshipUpdate.getRelationshipClass(), isTestMode);
      } else {
        addRelationships(relationshipUpdate.getRelationships(), isTestMode, urn);
      }
    }
  }

  /**
   * This method clears all the relationships from a source entity urn using REMOVE_ALL_EDGES_FROM_SOURCE.
   * @param urn entity urn could be either source or destination, depends on the RemovalOption
   * @param relationshipClass relationship that needs to be cleared
   * @param isTestMode whether to use test schema
   */
  public void clearRelationshipsByEntity(@Nonnull Urn urn,
      @Nonnull Class<? extends RecordTemplate> relationshipClass, boolean isTestMode) {
    RelationshipValidator.validateRelationshipSchema(relationshipClass, isRelationshipInV2(relationshipClass));
    SqlUpdate deletionSQL = _server.createSqlUpdate(SQLStatementUtils.deleteLocalRelationshipSQL(
        isTestMode ? SQLSchemaUtils.getTestRelationshipTableName(relationshipClass)
            : SQLSchemaUtils.getRelationshipTableName(relationshipClass), RemovalOption.REMOVE_ALL_EDGES_FROM_SOURCE));
    deletionSQL.setParameter(CommonColumnName.SOURCE, urn.toString());
    deletionSQL.execute();
  }

  /**
   * Persist the given list of relationships to the local relationship using REMOVE_ALL_EDGES_FROM_SOURCE.
   * @param relationships the list of relationships to be persisted
   * @param isTestMode whether to use test schema
   * @param urn Urn of the entity to update relationships.
   *            For Relationship V1: Optional, can be source or destination urn.
   *            For Relationship V2: Required, is the source urn.
   */
  public <RELATIONSHIP extends RecordTemplate> void addRelationships(@Nonnull List<RELATIONSHIP> relationships,
      boolean isTestMode, @Nullable Urn urn) {
    // split relationships by relationship type
    Map<String, List<RELATIONSHIP>> relationshipGroupMap = relationships.stream()
        .collect(Collectors.groupingBy(relationship -> relationship.getClass().getCanonicalName()));

    // validate if all relationship groups have valid urns
    relationshipGroupMap.values().forEach(relationshipGroup -> GraphUtils.checkSameSourceUrn(relationshipGroup, urn));

    relationshipGroupMap.values().forEach(relationshipGroup -> {
      addRelationshipGroup(relationshipGroup, isTestMode, urn);
    });
  }

  // This method only supports Relationship 1.0 (i.e. source present in model) ingestion using graph builders.
  @Override
  public <RELATIONSHIP extends RecordTemplate> void addRelationships(@Nonnull List<RELATIONSHIP> relationships,
      @Nonnull RemovalOption removalOption, boolean isTestMode) {
    addRelationships(relationships, isTestMode, null);
  }

  @Override
  public <RELATIONSHIP extends RecordTemplate> void removeRelationships(@Nonnull List<RELATIONSHIP> relationships) {
    removeRelationshipsV2(relationships, null);
  }

  public <RELATIONSHIP extends RecordTemplate> void removeRelationshipsV2(@Nonnull List<RELATIONSHIP> relationships, @Nullable Urn sourceUrn) {
    for (RELATIONSHIP relationship : relationships) {
      _server.createSqlUpdate(SQLStatementUtils.deleteLocalRelationshipSQL(SQLSchemaUtils.getRelationshipTableName(relationship),
              RemovalOption.REMOVE_ALL_EDGES_FROM_SOURCE_TO_DESTINATION))
          .setParameter(CommonColumnName.SOURCE, GraphUtils.getSourceUrnBasedOnRelationshipVersion(relationship, sourceUrn).toString())
          .setParameter(CommonColumnName.DESTINATION, getDestinationUrnFromRelationship(relationship).toString())
          .execute();
    }
  }

  @Override
  public <ENTITY extends RecordTemplate> void addEntities(@Nonnull List<ENTITY> entities) {
    throw new UnsupportedOperationException("Local relationship does not support adding entity. Please consider using metadata entity table.");
  }

  @Override
  public <URN extends Urn> void removeEntities(@Nonnull List<URN> urns) {
    throw new UnsupportedOperationException("Local relationship does not support removing entity. Please consider using metadata entity table.");
  }

  /**
   * Add the given list of relationships to the local relationship tables.
   * @param relationshipGroup the list of relationships to be persisted
   * @param isTestMode  whether to use test schema
   * @param urn the source urn to be used for the relationships. Optional for Relationship V1.
   *            Needed for Relationship V2 because source is not included in the relationshipV2 metadata.
   */
  private <RELATIONSHIP extends RecordTemplate> void addRelationshipGroup(@Nonnull final List<RELATIONSHIP> relationshipGroup,
      boolean isTestMode, @Nullable Urn urn) {
    if (relationshipGroup.size() == 0) {
      return;
    }

    RELATIONSHIP firstRelationship = relationshipGroup.get(0);
    RelationshipValidator.validateRelationshipSchema(firstRelationship.getClass(), isRelationshipInV2(firstRelationship.getClass()));

    // Remove some local relationships if needed before adding new relationships using REMOVE_ALL_EDGES_FROM_SOURCE.
    removeRelationshipsBySource(isTestMode ? SQLSchemaUtils.getTestRelationshipTableName(firstRelationship)
        : SQLSchemaUtils.getRelationshipTableName(firstRelationship), firstRelationship, urn);

    long now = Instant.now().toEpochMilli();

    for (RELATIONSHIP relationship : relationshipGroup) {
      // Relationship model V2 doesn't include source urn, it needs to be passed in.
      // For relationship model V1, this given urn can be source urn or destination urn.
      // For relationship model V2, this given urn can only be source urn.
      Urn source = GraphUtils.getSourceUrnBasedOnRelationshipVersion(relationship, urn);
      Urn destination = getDestinationUrnFromRelationship(relationship);

      _server.createSqlUpdate(SQLStatementUtils.insertLocalRelationshipSQL(
              isTestMode ? SQLSchemaUtils.getTestRelationshipTableName(relationship)
                  : SQLSchemaUtils.getRelationshipTableName(relationship)))
          .setParameter(CommonColumnName.METADATA, RecordUtils.toJsonString(relationship))
          .setParameter(CommonColumnName.SOURCE_TYPE, source.getEntityType())
          .setParameter(CommonColumnName.DESTINATION_TYPE, destination.getEntityType())
          .setParameter(CommonColumnName.SOURCE, source.toString())
          .setParameter(CommonColumnName.DESTINATION, destination.toString())
          .setParameter(CommonColumnName.LAST_MODIFIED_ON, new Timestamp(now))
          .setParameter(CommonColumnName.LAST_MODIFIED_BY, DEFAULT_ACTOR)
          .execute();
    }
  }

  /**
   * Process the relationship removal in the DB tableName based on the removal option.
   * @param tableName the table name of the relationship
   * @param relationship the relationship to be removed
   * @param urn the source urn to be used for the relationships. Optional for Relationship V1.
   *            Needed for Relationship V2 because source is not included in the relationshipV2 metadata.
   */
  private <RELATIONSHIP extends RecordTemplate> void removeRelationshipsBySource(@Nonnull String tableName,
      @Nonnull RELATIONSHIP relationship, @Nullable Urn urn) {
    SqlUpdate deletionSQL = _server.createSqlUpdate(SQLStatementUtils.deleteLocalRelationshipSQL(tableName, RemovalOption.REMOVE_ALL_EDGES_FROM_SOURCE));
    Urn source = GraphUtils.getSourceUrnBasedOnRelationshipVersion(relationship, urn);
    deletionSQL.setParameter(CommonColumnName.SOURCE, source.toString());
    deletionSQL.execute();
  }
}
