/*
 * Copyright 2018 Google, Inc.
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
 */

package app.tivi.datasources.trakt

import app.tivi.ShowFetcher
import app.tivi.data.DatabaseTransactionRunner
import app.tivi.data.daos.RelatedShowsDao
import app.tivi.data.daos.TiviShowDao
import app.tivi.data.entities.RelatedShowEntry
import app.tivi.data.entities.RelatedShowsListItem
import app.tivi.datasources.RefreshableDataSource
import app.tivi.extensions.fetchBodyWithRetry
import app.tivi.extensions.parallelForEach
import app.tivi.util.AppCoroutineDispatchers
import app.tivi.util.AppRxSchedulers
import com.uwetrottmann.trakt5.enums.Extended
import com.uwetrottmann.trakt5.services.Shows
import io.reactivex.Flowable
import kotlinx.coroutines.experimental.withContext
import javax.inject.Inject
import javax.inject.Provider

class RelatedShowsDataSource @Inject constructor(
    private val showDao: TiviShowDao,
    private val entryDao: RelatedShowsDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val showsService: Provider<Shows>,
    private val schedulers: AppRxSchedulers,
    private val dispatchers: AppCoroutineDispatchers,
    private val showFetcher: ShowFetcher
) : RefreshableDataSource<Long, List<RelatedShowsListItem>> {
    override suspend fun refresh(param: Long) {
        val related = withContext(dispatchers.io) {
            val show = showDao.getShowWithId(param)!!
            val results = showsService.get().related(show.traktId.toString(), 0, 10, Extended.NOSEASONS)
                    .fetchBodyWithRetry()

            results.mapIndexed { index, relatedShow ->
                // Now insert a placeholder for each show if needed
                val relatedShowId = showFetcher.insertPlaceholderIfNeeded(relatedShow)
                // Map to related show entry
                RelatedShowEntry(showId = param, otherShowId = relatedShowId, orderIndex = index)
            }.also {
                // Save map entities to db
                transactionRunner.runInTransaction {
                    entryDao.deleteWithShowId(param)
                    entryDao.insertAll(it)
                }
            }
        }

        // Finally trigger a refresh for each show
        related.parallelForEach {
            showFetcher.update(it.otherShowId)
        }
    }

    override fun data(param: Long): Flowable<List<RelatedShowsListItem>> {
        return entryDao.entries(param)
                .subscribeOn(schedulers.io)
                .startWith(Flowable.just(emptyList()))
                .distinctUntilChanged()
    }
}