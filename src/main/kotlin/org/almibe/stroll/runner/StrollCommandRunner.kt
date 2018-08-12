/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

package org.almibe.stroll.runner

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.PersistentEntityStore
import jetbrains.exodus.entitystore.StoreTransaction
import org.almibe.stroll.*
import org.almibe.stroll.loader.CommandArguments

class StrollCommandRunner {
    fun runCommandArguments(entityStore: PersistentEntityStore, commandArguments: CommandArguments): StrollResult {
        return when (commandArguments.commandType) {
            CommandType.NEW -> handleNew(entityStore, commandArguments)
            CommandType.UPDATE -> handleUpdate(entityStore, commandArguments)
            CommandType.SET -> handleSet(entityStore, commandArguments)
            CommandType.DELETE -> handleDelete(entityStore, commandArguments)
            CommandType.FIND -> handleFind(entityStore, commandArguments)
            CommandType.SIMPLE -> handleSimple(entityStore, commandArguments)
            else -> throw RuntimeException("Unexpected command type ${commandArguments.commandType}")
        }
    }

    private fun handleNew(entityStore: PersistentEntityStore, commandArguments: CommandArguments): NewResult {
        return entityStore.computeInTransaction { transaction ->
            val entity = transaction.newEntity(commandArguments.entityType)
            setPropertiesAndLinks(transaction, entity, commandArguments)
            NewResult(entityToReadEntity(entity))
        }
    }

    private fun handleUpdate(entityStore: PersistentEntityStore, commandArguments: CommandArguments): UpdateResult {
        return entityStore.computeInTransaction { transaction ->
            val entity = transaction.getEntity(transaction.toEntityId(commandArguments.entityId))
            setPropertiesAndLinks(transaction, entity, commandArguments)
            UpdateResult(entityToReadEntity(entity))
        }
    }

    private fun handleSet(entityStore: PersistentEntityStore, commandArguments: CommandArguments): SetResult {
        return entityStore.computeInTransaction { transaction ->
            val entity = transaction.getEntity(transaction.toEntityId(commandArguments.entityId))
            clearPropertiesAndLinks(entity)
            setPropertiesAndLinks(transaction, entity, commandArguments)
            SetResult(entityToReadEntity(entity))
        }
    }

    private fun handleDelete(entityStore: PersistentEntityStore, commandArguments: CommandArguments): DeleteResult {
        return entityStore.computeInTransaction { transaction ->
            var total = 0
            commandArguments.entityIds.forEach { entityId: String ->
                val entity = transaction.getEntity(transaction.toEntityId(entityId))
                entity.delete()
                total++
            }
            DeleteResult(total)
        }
    }

    private fun handleFind(entityStore: PersistentEntityStore, commandArguments: CommandArguments): FindResult {
        val resultLists: MutableList<ReadEntity> = mutableListOf()
        val entityType = commandArguments.entityType
        return entityStore.computeInReadonlyTransaction { transaction ->
            commandArguments.properties.forEach { name, property ->
                val result = when(property.type) {
                    "Int" ->
                        transaction.find(entityType, name, property.value.toInt()).toList()
                    "Double" ->
                        transaction.find(entityType, name, property.value.toDouble()).toList()
                    "Long" ->
                        transaction.find(entityType, name, property.value.toLong()).toList()
                    "String" ->
                        transaction.find(entityType, name, property.value.toString()).toList()
                    "Boolean" ->
                        transaction.find(entityType, name, property.value.toBoolean()).toList()
                    else -> throw RuntimeException()
                }
                result.forEach { entity: Entity ->
                    resultLists.add(entityToReadEntity(entity))
                }
            }

//            commandArguments.link.forEach { link ->
//                val links = transaction.findLinks(entityType,
//                        transaction.getEntity(transaction.toEntityId(link.value.tokenContent)),
//                        link.key)
//                resultLists.add(links.toList())
//            }
//
//            commandArguments.links.forEach { link ->
//                val links = transaction.findLinks(entityType,
//                        transaction.getEntity(transaction.toEntityId(link.second.tokenContent)),
//                        link.first)
//                resultLists.add(links.toList())
//            }
//
//            commandArguments.propertyExistsCheck.forEach { propertyName ->
//                val withProp = transaction.findWithProp(entityType, propertyName)
//                resultLists.add(withProp.toList())
//            }
//
//            commandArguments.linkExistsCheck.forEach { linkName ->
//                val withLinks = transaction.findWithLinks(entityType, linkName)
//                resultLists.add(withLinks.toList())
//            }
            //TODO eventually handle ranges and startsWith here
            FindResult(resultLists)
        }
    }

    private fun handleSimple(entityStore: PersistentEntityStore, commandArguments: CommandArguments): SimpleResult {
        TODO()
    }

    private fun entityToReadEntity(entity: Entity): ReadEntity {
        val properties: Set<ReadProperty> = entity.propertyNames.map { propertyName: String ->
            val property = entity.getProperty(propertyName)
            ReadProperty(propertyName, getPropertyType(property!!), property.toString())
        }.toSet()
        val links = mutableSetOf<ReadLink>()
        entity.linkNames.forEach { linkName: String ->
            entity.getLinks(linkName).forEach { linkedEntity: Entity ->
                links.add(ReadLink(linkName, linkedEntity.toIdString()))
            }
        }

        return ReadEntity(entity.type, entity.toIdString(), properties, links)
    }

    private fun getPropertyType(property: Any): String = when (property) {
        is String -> "String"
        is Int -> "Int"
        is Double -> "Double"
        is Long -> "Long"
        is Boolean -> "Boolean"
        else -> property.javaClass.canonicalName
    }

    private fun setPropertiesAndLinks(transaction: StoreTransaction, entity: Entity, commandArguments: CommandArguments) {
        commandArguments.properties.forEach { name, property ->
            when (property.type) {
                "Double" -> entity.setProperty(name, property.value.toDouble())
                "Long" -> entity.setProperty(name, property.value.trim('L').toLong())
                "Int" -> entity.setProperty(name, property.value.toInt())
                "String" -> entity.setProperty(name, property.value)
                "Boolean" -> entity.setProperty(name, property.value.toBoolean())
                "Other" -> entity.setProperty(name, property.value)
                else -> {
                    throw RuntimeException("Unexpected property $name - $property.")
                }
            }
        }
        commandArguments.link.forEach {
            val linkedEntity = transaction.getEntity(transaction.toEntityId(it.value))
            entity.setLink(it.key, linkedEntity)
        }
        commandArguments.links.forEach {
            val linkedEntity = transaction.getEntity(transaction.toEntityId(it.second))
            entity.addLink(it.first, linkedEntity)
        }
    }

    private fun clearPropertiesAndLinks(entity: Entity) {
        entity.propertyNames.forEach {
            entity.deleteProperty(it)
        }
        entity.linkNames.forEach {
            entity.deleteLinks(it)
        }
    }
}
