/*
 * Copyright 2017 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apicurio.hub.api.storage.jdbc;

import java.util.Collection;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.sql.DataSource;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.result.ResultIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.apicurio.hub.api.beans.ApiDesign;
import io.apicurio.hub.api.beans.LinkedAccount;
import io.apicurio.hub.api.beans.LinkedAccountType;
import io.apicurio.hub.api.config.HubApiConfiguration;
import io.apicurio.hub.api.exceptions.AlreadyExistsException;
import io.apicurio.hub.api.exceptions.NotFoundException;
import io.apicurio.hub.api.storage.IStorage;
import io.apicurio.hub.api.storage.StorageException;

/**
 * A JDBC/SQL implementation of the storage layer.
 * @author eric.wittmann@gmail.com
 */
@ApplicationScoped
public class JdbcStorage implements IStorage {

    private static Logger logger = LoggerFactory.getLogger(JdbcStorage.class);

    @Inject
    private HubApiConfiguration config;
    @Resource(mappedName="java:jboss/datasources/ApicurioDS")
    private DataSource dataSource;
    
    private ISqlStatements sqlStatements;
    private Jdbi jdbi;
    
    @PostConstruct
    public void postConstruct() {
        logger.debug("JDBC Storage constructed successfully.");

        jdbi = Jdbi.create(dataSource);

        switch (config.getJdbcType()) {
            case "h2":
                sqlStatements = new H2SqlStatements();
                break;
            case "mysql5":
                sqlStatements = new MySQL5SqlStatements();
                break;
            case "postgresql9":
                sqlStatements = new PostgreSQL9SqlStatements();
                break;
            default:
                throw new RuntimeException("Unsupported JDBC type: " + config.getJdbcType());
        }
        
        if (config.isJdbcInit()) {
            if (!isDatabaseInitialized()) {
                logger.debug("Database not initialized.");
                initializeDatabase();
            } else {
                logger.debug("Database was already initialized, skipping.");
            }
        }
    }
    
    /**
     * @return true if the database has already been initialized
     */
    private boolean isDatabaseInitialized() {
        logger.debug("Checking to see if the DB is initialized.");
        return this.jdbi.withHandle(handle -> {
            ResultIterable<Integer> result = handle.createQuery(this.sqlStatements.isDatabaseInitialized()).mapTo(Integer.class);
            return result.findOnly().intValue() > 0;
        });
    }

    /**
     * Initializes the database by executing a number of DDL statements.
     */
    private void initializeDatabase() {
        logger.info("Initializing the Apicurio Hub API database.");
        logger.info("\tDatabase type: " + config.getJdbcType());
        
        final List<String> statements = this.sqlStatements.databaseInitialization();
        logger.debug("---");
        this.jdbi.withHandle( handle -> {
            statements.forEach( statement -> {
                logger.debug(statement);
                handle.createUpdate(statement).execute();
            });
            return null;
        });
        logger.debug("---");
    }
    
    /**
     * @see io.apicurio.hub.api.storage.IStorage#createLinkedAccount(java.lang.String, io.apicurio.hub.api.beans.LinkedAccount)
     */
    @Override
    public void createLinkedAccount(String userId, LinkedAccount account)
            throws AlreadyExistsException, StorageException {
        logger.debug("Inserting a Linked Account {} for {}", account.getType().name(), userId);
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.insertLinkedAccount();
                handle.createUpdate(statement)
                      .bind(0, userId)
                      .bind(1, account.getType().name())
                      .bind(2, account.getLinkedOn())
                      .bind(3, account.getUsedOn())
                      .bind(4, account.getNonce())
                      .execute();
                return null;
            });
        } catch (Exception e) {
            if (e.getMessage().contains("Unique")) {
                throw new AlreadyExistsException();
            } else {
                throw new StorageException("Error inserting API design.", e);
            }
        }
    }
    
    /**
     * @see io.apicurio.hub.api.storage.IStorage#getLinkedAccount(java.lang.String, io.apicurio.hub.api.beans.LinkedAccountType)
     */
    @Override
    public LinkedAccount getLinkedAccount(String userId, LinkedAccountType type)
            throws StorageException, NotFoundException {
        logger.debug("Selecting a single Linked Account: {}::{}", userId, type.name());
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectLinkedAccountByType();
                return handle.createQuery(statement)
                        .bind(0, userId)
                        .bind(1, type.name())
                        .mapToBean(LinkedAccount.class)
                        .findOnly();
            });
        } catch (IllegalStateException e) {
            throw new NotFoundException();
        } catch (Exception e) {
            throw new StorageException("Error getting linked account.");
        }
    }
    
    /**
     * @see io.apicurio.hub.api.storage.IStorage#listLinkedAccounts(java.lang.String)
     */
    @Override
    public Collection<LinkedAccount> listLinkedAccounts(String userId) throws StorageException {
        logger.debug("Getting a list of all Linked Accouts for {}.", userId);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectLinkedAccounts();
                return handle.createQuery(statement)
                        .bind(0, userId)
                        .mapToBean(LinkedAccount.class)
                        .list();
            });
        } catch (Exception e) {
            throw new StorageException("Error listing linked accounts.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.storage.IStorage#deleteLinkedAccount(java.lang.String, io.apicurio.hub.api.beans.LinkedAccountType)
     */
    @Override
    public void deleteLinkedAccount(String userId, LinkedAccountType type)
            throws StorageException, NotFoundException {
        logger.debug("Deleting a Linked Account: {}::{}", userId, type.name());
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.deleteLinkedAccount();
                int rowCount = handle.createUpdate(statement)
                      .bind(0, userId)
                      .bind(1, type.name())
                      .execute();
                if (rowCount == 0) {
                    throw new NotFoundException();
                }
                return null;
            });
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Error deleting a Linked Account", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.storage.IStorage#deleteLinkedAccounts(java.lang.String)
     */
    @Override
    public void deleteLinkedAccounts(String userId) throws StorageException {
        logger.debug("Deleting all Linked Accounts for {}", userId);
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.deleteLinkedAccounts();
                handle.createUpdate(statement)
                      .bind(0, userId)
                      .execute();
                return null;
            });
        } catch (Exception e) {
            throw new StorageException("Error deleting Linked Accounts", e);
        }        
    }
    
    /**
     * @see io.apicurio.hub.api.storage.IStorage#updateLinkedAccount(java.lang.String, io.apicurio.hub.api.beans.LinkedAccount)
     */
    @Override
    public void updateLinkedAccount(String userId, LinkedAccount account) throws NotFoundException, StorageException {
        logger.debug("Updating a Linked Account: {}::{}", userId, account.getType().name());
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.updateLinkedAccount();
                int rowCount = handle.createUpdate(statement)
                        .bind(0, account.getUsedOn())
                        .bind(1, account.getLinkedOn())
                        .bind(2, account.getNonce())
                        .bind(3, userId)
                        .bind(4, account.getType().name())
                        .execute();
                if (rowCount == 0) {
                    throw new NotFoundException();
                }
                return null;
            });
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Error updating a Linked Account.", e);
        }
    }

    /**
     * @see io.apicurio.hub.api.storage.IStorage#getApiDesign(java.lang.String, java.lang.String)
     */
    @Override
    public ApiDesign getApiDesign(String userId, String designId) throws NotFoundException, StorageException {
        logger.debug("Selecting a single API Design: {}", designId);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectApiDesignById();
                return handle.createQuery(statement)
                        .bind(0, Long.valueOf(designId))
                        .bind(1, userId)
                        .mapToBean(ApiDesign.class)
                        .findOnly();
            });
        } catch (IllegalStateException e) {
            throw new NotFoundException();
        } catch (Exception e) {
            throw new StorageException("Error getting API design.");
        }
    }

    /**
     * @see io.apicurio.hub.api.storage.IStorage#createApiDesign(java.lang.String, io.apicurio.hub.api.beans.ApiDesign)
     */
    @Override
    public String createApiDesign(String userId, ApiDesign design) throws AlreadyExistsException, StorageException {
        logger.debug("Inserting an API Design: {}", design.getRepositoryUrl());
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.insertApiDesign();
                String designId = handle.createUpdate(statement)
                      .bind(0, design.getName())
                      .bind(1, design.getDescription())
                      .bind(2, design.getRepositoryUrl())
                      .bind(3, design.getCreatedBy())
                      .bind(4, design.getCreatedOn())
                      .bind(5, design.getModifiedBy())
                      .bind(6, design.getModifiedOn())
                      .executeAndReturnGeneratedKeys("id")
                      .mapTo(String.class)
                      .findOnly();

                // Insert a row in the ACL table with role 'owner' for this API
                statement = sqlStatements.insertAcl();
                handle.createUpdate(statement)
                      .bind(0, userId)
                      .bind(1, Long.parseLong(designId))
                      .bind(2, "owner")
                      .execute();
                
                return designId;
            });
        } catch (Exception e) {
            if (e.getMessage().contains("Unique")) {
                throw new AlreadyExistsException();
            } else {
                throw new StorageException("Error inserting API design.", e);
            }
        }
    }

    /**
     * @see io.apicurio.hub.api.storage.IStorage#deleteApiDesign(java.lang.String, java.lang.String)
     */
    @Override
    public void deleteApiDesign(String userId, String designId) throws NotFoundException, StorageException {
        logger.debug("Deleting an API Design: {}", designId);
        try {
            this.jdbi.withHandle( handle -> {
                // Check for permissions first
                String statement = sqlStatements.hasWritePermission();
                int count = handle.createQuery(statement)
                    .bind(0, Long.valueOf(designId))
                    .bind(1, userId)
                    .mapTo(Integer.class).findOnly();
                if (count == 0) {
                    throw new NotFoundException();
                }

                // If OK then delete ACL entries
                statement = sqlStatements.clearAcl();
                handle.createUpdate(statement).bind(0, Long.valueOf(designId)).execute();
                
                // Then delete the api design itself
                statement = sqlStatements.deleteApiDesign();
                int rowCount = handle.createUpdate(statement)
                      .bind(0, Long.valueOf(designId))
                      .bind(1, userId)
                      .execute();
                if (rowCount == 0) {
                    throw new NotFoundException();
                }
                return null;
            });
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Error deleting an API design.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.storage.IStorage#updateApiDesign(java.lang.String, io.apicurio.hub.api.beans.ApiDesign)
     */
    @Override
    public void updateApiDesign(String userId, ApiDesign design) throws NotFoundException, StorageException {
        logger.debug("Updating an API Design: {}", design.getId());
        try {
            this.jdbi.withHandle( handle -> {
                // Check for permissions first
                String statement = sqlStatements.hasWritePermission();
                int count = handle.createQuery(statement)
                    .bind(0, Long.valueOf(design.getId()))
                    .bind(1, userId)
                    .mapTo(Integer.class).findOnly();
                if (count == 0) {
                    throw new NotFoundException();
                }

                // Then perform the update
                statement = sqlStatements.updateApiDesign();
                int rowCount = handle.createUpdate(statement)
                        .bind(0, design.getName())
                        .bind(1, design.getDescription())
                        .bind(2, design.getModifiedBy())
                        .bind(3, design.getModifiedOn())
                        .bind(4, Long.valueOf(design.getId()))
                        .execute();
                if (rowCount == 0) {
                    throw new NotFoundException();
                }
                return null;
            });
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Error updating an API design.", e);
        }
    }

    /**
     * @see io.apicurio.hub.api.storage.IStorage#listApiDesigns(java.lang.String)
     */
    @Override
    public Collection<ApiDesign> listApiDesigns(String userId) throws StorageException {
        logger.debug("Getting a list of all API designs.");
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectApiDesigns();
                return handle.createQuery(statement)
                        .bind(0, userId)
                        .mapToBean(ApiDesign.class)
                        .list();
            });
        } catch (Exception e) {
            throw new StorageException("Error listing API designs.", e);
        }
    }

}
