package com.watermark.camera.ui.gallery

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.watermark.camera.R
import com.watermark.camera.databinding.FragmentGalleryBinding
import com.watermark.camera.ui.common.BaseFragment
import com.watermark.camera.ui.common.Feedback
import com.watermark.camera.ui.detail.PhotoDetailFragment
import com.watermark.camera.util.ViewAnim
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 相册页。
 *
 * 重做要点（对应审计 C1–C7）：
 * - 数据来自 Room，只包含本应用拍摄的照片；
 * - 按日期分组，分组头占满整行并吸顶；
 * - 网格列数可调（2–5），支持搜索、全选、反选；
 * - 滚动到底自动加载下一页，不再有 200 张上限；
 * - 删除结果如实反馈成败张数。
 */
@AndroidEntryPoint
class GalleryFragment : BaseFragment<FragmentGalleryBinding>() {

    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter
    private var layoutManager: GridLayoutManager? = null

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentGalleryBinding = FragmentGalleryBinding.inflate(inflater, container, false)

    override fun initViews() {
        applyGalleryInsets()
        setupRecyclerView()
        setupSearch()
        setupButtons()
        ensureMediaPermission()
    }

    // region 相册权限（按需请求：只在打开相册时申请，规范 §3）

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Android 14 允许“部分照片”：只要拿到任一读权限就继续
        if (hasMediaAccess()) {
            viewModel.refresh()
        } else {
            Feedback.needsPermission(binding.root, getString(R.string.permission_banner_gallery))
        }
    }

    /** Android 14 起支持“部分照片”授权，故一并申请 [Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED]。 */
    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasMediaAccess(): Boolean = mediaPermissions().any {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureMediaPermission() {
        if (hasMediaAccess()) return
        mediaPermissionLauncher.launch(mediaPermissions())
    }

    // endregion

    private fun applyGalleryInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.galleryTopBar.setPadding(
                binding.galleryTopBar.paddingLeft,
                bars.top + 4,
                binding.galleryTopBar.paddingRight,
                binding.galleryTopBar.paddingBottom
            )
            binding.galleryBottomBar.setPadding(
                binding.galleryBottomBar.paddingLeft,
                binding.galleryBottomBar.paddingTop,
                binding.galleryBottomBar.paddingRight,
                bars.bottom + 4
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.items.collect { items ->
                        adapter.submitList(items)
                        val isEmpty = items.isEmpty()
                        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.photos.collect { photos ->
                        binding.tvPhotoCount.text = if (photos.isEmpty()) "" else "共 ${photos.size} 张"
                    }
                }

                launch {
                    viewModel.isMultiSelectMode.collect { multi ->
                        binding.btnMultiSelect.text = if (multi) "取消" else "多选"
                        binding.layoutSelectionActions.visibility =
                            if (multi) View.VISIBLE else View.GONE
                        adapter.setMultiSelect(multi)
                    }
                }

                launch {
                    viewModel.selectedUris.collect { selected ->
                        binding.tvSelectedCount.text = "已选 ${selected.size}"
                        binding.btnConfirmSelection.isEnabled = selected.isNotEmpty()
                        binding.btnDeleteSelected.isEnabled = selected.isNotEmpty()
                    }
                }

                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.progressBar.visibility =
                            if (loading) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.columns.collect { columns ->
                        applyColumns(columns)
                    }
                }

                launch {
                    viewModel.event.collect { event ->
                        event?.let { handleEvent(it) }
                    }
                }

                launch {
                    viewModel.message.collect { msg ->
                        msg?.let {
                            Feedback.light(binding.root, it)
                            viewModel.consumeMessage()
                        }
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = GalleryAdapter { uri ->
            if (viewModel.isMultiSelectMode.value) {
                viewModel.toggleSelection(uri)
            } else {
                viewModel.openPhotoDetail(uri)
            }
        }
        binding.recyclerView.adapter = adapter
        applyColumns(viewModel.columns.value)

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = layoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= adapter.itemCount - 6) {
                    viewModel.loadMore()
                }
            }
        })
    }

    /** 应用列数，并按列宽把格子设成正方形。 */
    private fun applyColumns(columns: Int) {
        val lm = layoutManager ?: GridLayoutManager(requireContext(), columns).also {
            layoutManager = it
            binding.recyclerView.layoutManager = it
        }
        if (lm.spanCount != columns) {
            lm.spanCount = columns
        }
        lm.spanSizeLookup = adapter.spanSizeLookup(columns)

        binding.recyclerView.post {
            val width = binding.recyclerView.width
            if (width > 0) {
                val padding = binding.recyclerView.paddingLeft + binding.recyclerView.paddingRight
                val cell = (width - padding) / columns
                adapter.cellSizePx = cell.coerceAtLeast(1)
            }
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setQuery(s?.toString().orEmpty())
            }
        })
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        binding.btnMultiSelect.setOnClickListener { viewModel.toggleMultiSelectMode() }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnInvertSelection.setOnClickListener { viewModel.invertSelection() }
        binding.btnClearSelection.setOnClickListener { viewModel.clearSelection() }

        binding.btnConfirmSelection.setOnClickListener { viewModel.sendSelectionToCollage() }

        binding.btnDeleteSelected.setOnClickListener {
            val n = viewModel.selectedUris.value.size
            if (n <= 0) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("删除照片")
                .setMessage("确定删除选中的 $n 张照片？此操作不可恢复。")
                .setPositiveButton("删除") { _, _ -> viewModel.deleteSelected() }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnRefresh.setOnClickListener { viewModel.refresh() }

        binding.btnColumns.setOnClickListener { showColumnPicker() }

        runCatching {
            ViewAnim.attachPressScale(
                binding.btnRefresh,
                binding.btnMultiSelect,
                binding.btnClearSelection,
                binding.btnDeleteSelected,
                binding.btnConfirmSelection,
                binding.btnBack,
                binding.btnSelectAll,
                binding.btnInvertSelection,
                binding.btnColumns
            )
        }
    }

    private fun showColumnPicker() {
        val options = (GalleryViewModel.MIN_COLUMNS..GalleryViewModel.MAX_COLUMNS)
            .map { "$it 列" }
            .toTypedArray()
        val current = viewModel.columns.value - GalleryViewModel.MIN_COLUMNS
        AlertDialog.Builder(requireContext())
            .setTitle("每行显示")
            .setSingleChoiceItems(options, current) { dialog, which ->
                viewModel.setColumns(which + GalleryViewModel.MIN_COLUMNS)
                dialog.dismiss()
            }
            .show()
    }

    private fun handleEvent(event: GalleryEvent) {
        when (event) {
            is GalleryEvent.NavigateToDetail -> {
                val detail = PhotoDetailFragment.newInstance(event.uri, ArrayList(event.allUris))
                parentFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment, detail)
                    .addToBackStack("detail")
                    .commit()
                viewModel.consumeEvent()
            }
            is GalleryEvent.SendToCollage -> {
                try {
                    val collage =
                        com.watermark.camera.ui.collage.CollageFragment.newInstance(event.uris)
                    requireActivity().supportFragmentManager.beginTransaction()
                        .replace(R.id.nav_host_fragment, collage, "collage")
                        .addToBackStack("collage")
                        .commitAllowingStateLoss()
                } catch (e: Exception) {
                    android.util.Log.e("GalleryFragment", "collage", e)
                    Feedback.actionable(
                        binding.root,
                        "${getString(R.string.feedback_collage_open_failed)}: ${e.message}"
                    )
                }
                viewModel.consumeEvent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从相机页返回时可能有新照片；Room 是数据源，刷新一次增量导入即可
        viewModel.refresh()
    }
}
