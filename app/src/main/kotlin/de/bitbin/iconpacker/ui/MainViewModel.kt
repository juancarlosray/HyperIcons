package de.bitbin.iconpacker.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.bitbin.iconpacker.apps.InstalledApp
import de.bitbin.iconpacker.apps.InstalledApps
import de.bitbin.iconpacker.data.AppDatabase
import de.bitbin.iconpacker.data.Assignment
import de.bitbin.iconpacker.data.AssignmentSource
import de.bitbin.iconpacker.data.Settings
import de.bitbin.iconpacker.iconpack.AppFilterParser
import de.bitbin.iconpacker.iconpack.ComponentKey
import de.bitbin.iconpacker.iconpack.IconPack
import de.bitbin.iconpacker.iconpack.IconPackDiscovery
import de.bitbin.iconpacker.iconpack.InstalledIconPack
import de.bitbin.iconpacker.mtz.MtzBuilder
import de.bitbin.iconpacker.picker.IconBitmapStore
import de.bitbin.iconpacker.theme.ApplyResult
import de.bitbin.iconpacker.theme.ThemeApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class RowStatus { UNMATCHED, MATCHED, MANUAL, SKIPPED }

private fun sortGroup(status: RowStatus): Int = when (status) {
    RowStatus.UNMATCHED -> 0
    RowStatus.MATCHED, RowStatus.MANUAL -> 1
    RowStatus.SKIPPED -> 2
}

data class AppRow(
    val app: InstalledApp,
    val assignment: Assignment?,
    val status: RowStatus,
)

data class UiState(
    val loading: Boolean = true,
    val availablePacks: List<InstalledIconPack> = emptyList(),
    val selectedPack: String? = null,
    val iconPack: IconPack? = null,
    val noPacksInstalled: Boolean = false,
    val rows: List<AppRow> = emptyList(),
    val statusMessage: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.assignments()
    private val bitmaps = IconBitmapStore(app)
    private val settings = Settings(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, statusMessage = null)
            val ctx = getApplication<Application>()

            val packs = withContext(Dispatchers.IO) { IconPackDiscovery.listInstalled(ctx) }
            if (packs.isEmpty()) {
                _state.value = UiState(loading = false, noPacksInstalled = true)
                return@launch
            }

            val selected = settings.selectedPack
                ?.takeIf { sel -> packs.any { it.packageName == sel } }
                ?: packs.first().packageName.also { settings.selectedPack = it }

            val pack = withContext(Dispatchers.IO) {
                runCatching { AppFilterParser.load(ctx, selected) }.getOrNull()
            }
            if (pack == null) {
                _state.value = UiState(
                    loading = false,
                    availablePacks = packs,
                    selectedPack = selected,
                    statusMessage = "No se pudo cargar $selected",
                )
                return@launch
            }

            val apps = withContext(Dispatchers.IO) { InstalledApps.query(ctx) }
            val stored = dao.getAll()
                .filter { it.iconPackPackage == selected }
                .associateBy { ComponentKey(it.pkg, it.activity) }

            android.util.Log.w("IconPacker", "pack ${pack.packageName} mappings=${pack.mappings.size}, installed apps=${apps.size}")

            val rows = apps.map { app ->
                val existing = stored[app.key]
                if (existing != null) {
                    AppRow(app, existing, statusOf(existing))
                } else {
                    val auto = pack.mappings[app.key]
                        ?: pack.mappings.entries.firstOrNull { it.key.pkg == app.key.pkg }?.value
                    if (auto != null) {
                        val row = Assignment(
                            pkg = app.key.pkg,
                            activity = app.key.activity,
                            drawable = auto,
                            source = AssignmentSource.AUTO,
                            iconPackPackage = pack.packageName,
                        )
                        dao.upsert(row)
                        AppRow(app, row, RowStatus.MATCHED)
                    } else {
                        AppRow(app, null, RowStatus.UNMATCHED)
                    }
                }
            }.sortedWith(compareBy({ sortGroup(it.status) }, { it.app.label.lowercase() }))

            val matched = rows.count { it.status == RowStatus.MATCHED }
            android.util.Log.w("IconPacker", "matched $matched of ${rows.size} apps against ${pack.packageName}")

            _state.value = UiState(
                loading = false,
                availablePacks = packs,
                selectedPack = selected,
                iconPack = pack,
                rows = rows,
            )
        }
    }

    fun selectPack(packageName: String) {
        if (packageName == _state.value.selectedPack) return
        viewModelScope.launch {
            settings.selectedPack = packageName
            reload()
        }
    }

    fun clearAssignmentsForCurrentPack() {
        val current = _state.value.selectedPack ?: return
        viewModelScope.launch {
            dao.getAll().filter { it.iconPackPackage == current }.forEach {
                bitmaps.delete(ComponentKey(it.pkg, it.activity))
                dao.delete(it.pkg, it.activity)
            }
            reload()
        }
    }

    private fun statusOf(a: Assignment): RowStatus = when (a.source) {
        AssignmentSource.AUTO -> RowStatus.MATCHED
        AssignmentSource.MANUAL -> RowStatus.MANUAL
        AssignmentSource.SKIP -> RowStatus.SKIPPED
    }

    fun onIconPicked(key: ComponentKey, bitmap: Bitmap) {
        val pack = _state.value.selectedPack ?: return
        viewModelScope.launch {
            bitmaps.save(key, bitmap)
            val a = Assignment(
                pkg = key.pkg,
                activity = key.activity,
                drawable = null,
                source = AssignmentSource.MANUAL,
                iconPackPackage = pack,
            )
            dao.upsert(a)
            patchRow(key) { it.copy(assignment = a, status = RowStatus.MANUAL) }
        }
    }

    fun onSkip(key: ComponentKey) {
        val pack = _state.value.selectedPack ?: return
        viewModelScope.launch {
            val a = Assignment(
                pkg = key.pkg,
                activity = key.activity,
                drawable = null,
                source = AssignmentSource.SKIP,
                iconPackPackage = pack,
            )
            dao.upsert(a)
            patchRow(key) { it.copy(assignment = a, status = RowStatus.SKIPPED) }
        }
    }

    fun onReset(key: ComponentKey) {
        viewModelScope.launch {
            bitmaps.delete(key)
            dao.delete(key.pkg, key.activity)
            reload()
        }
    }

    fun applyTheme(launchContext: android.content.Context) {
        val pack = _state.value.selectedPack ?: return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _state.value = _state.value.copy(statusMessage = "Generando tema…")
            val assignments = dao.getAll().filter { it.iconPackPackage == pack }
            val mtzDir = File(ctx.cacheDir, "mtz").apply { mkdirs() }
            val out = File(mtzDir, "icons_${System.currentTimeMillis()}.mtz")
            withContext(Dispatchers.IO) {
                MtzBuilder(ctx, pack).build(assignments, out)
            }
            val result = ThemeApplier.apply(launchContext, out)
            val msg = when (result) {
                is ApplyResult.Launched -> "Abierto con ${result.via}"
                is ApplyResult.NoHandler -> "No hay app para abrirlo. Guardado en ${result.exportedTo.absolutePath}"
            }
            _state.value = _state.value.copy(statusMessage = msg)
        }
    }

    fun exportTheme() {
        val pack = _state.value.selectedPack ?: return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _state.value = _state.value.copy(statusMessage = "Generando tema…")
            val assignments = dao.getAll().filter { it.iconPackPackage == pack }
            val mtzDir = File(ctx.cacheDir, "mtz").apply { mkdirs() }
            val out = File(mtzDir, "icons_${System.currentTimeMillis()}.mtz")
            withContext(Dispatchers.IO) {
                MtzBuilder(ctx, pack).build(assignments, out)
            }
            val saved = withContext(Dispatchers.IO) { ThemeApplier.saveToDownloads(ctx, out) }
            _state.value = _state.value.copy(
                statusMessage = "Guardado en ${saved.absolutePath}",
            )
        }
    }

    private fun patchRow(key: ComponentKey, transform: (AppRow) -> AppRow) {
        val newRows = _state.value.rows
            .map { if (it.app.key == key) transform(it) else it }
            .sortedWith(compareBy({ sortGroup(it.status) }, { it.app.label.lowercase() }))
        _state.value = _state.value.copy(rows = newRows)
    }
}
