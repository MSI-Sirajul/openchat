package com.msi.openchat.feature.permission

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.MainActivity
import com.example.R
import com.example.databinding.FragmentPermissionOnboardingBinding
import com.msi.openchat.core.permission.PermissionManager
import kotlinx.coroutines.launch

class PermissionOnboardingFragment : Fragment() {

    private var _binding: FragmentPermissionOnboardingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PermissionOnboardingViewModel by viewModels()

    private val permissionQueue = ArrayDeque<String>()
    private val askedPermissionsThisSession = mutableSetOf<String>()
    private var activePermission: String? = null
    private var isSequentialFlowRunning = false
    private var openedSettingsForRemaining = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val ctx = context ?: return@registerForActivityResult
        val completedPerm = activePermission
        activePermission = null

        if (completedPerm != null) {
            askedPermissionsThisSession.add(completedPerm)
            viewModel.onPermissionResult(completedPerm, isGranted)
        }
        viewModel.refreshPermissions(ctx)

        if (isSequentialFlowRunning) {
            launchNextQueuedPermission()
        } else {
            val state = viewModel.uiState.value
            if (state.allGranted) {
                viewModel.onDoneClicked(ctx)
            } else if (!isGranted) {
                Toast.makeText(ctx, getString(R.string.permissions_denied_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private lateinit var adapter: PermissionOnboardingAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPermissionOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupInsets()
        setupBackPressHandling()
        setupRecyclerView()
        setupButtons()
        observeViewModel()

        viewModel.loadPermissions(requireContext())
    }

    override fun onResume() {
        super.onResume()
        val ctx = context ?: return
        viewModel.refreshPermissions(ctx)
        val state = viewModel.uiState.value
        if ((openedSettingsForRemaining || state.hasInteracted) && state.allGranted) {
            openedSettingsForRemaining = false
            viewModel.onDoneClicked(ctx)
        }
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootPermissionOnboarding) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
    }

    private fun setupBackPressHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    viewModel.onSkipClicked(requireContext())
                }
            }
        )
    }

    private fun setupRecyclerView() {
        adapter = PermissionOnboardingAdapter { item ->
            val ctx = requireContext()
            if (PermissionManager.hasPermission(ctx, item.permission)) {
                Toast.makeText(
                    ctx,
                    getString(R.string.permissions_already_enabled_toast, item.title),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                permissionQueue.clear()
                isSequentialFlowRunning = false
                if (isPermanentlyDeniedAfterPrompt(item.permission)) {
                    openAppPermissionSettings()
                } else {
                    activePermission = item.permission
                    requestPermissionLauncher.launch(item.permission)
                }
            }
        }

        binding.recyclerPermissions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PermissionOnboardingFragment.adapter
            setHasFixedSize(false)
        }
    }

    private fun setupButtons() {
        binding.btnAllowPermissions.setOnClickListener {
            startSequentialPermissionFlow()
        }

        binding.btnSkipPermissions.setOnClickListener {
            permissionQueue.clear()
            isSequentialFlowRunning = false
            viewModel.onSkipClicked(requireContext())
        }
    }

    private fun startSequentialPermissionFlow() {
        val ctx = requireContext()
        viewModel.refreshPermissions(ctx)
        val missing = viewModel.uiState.value.items
            .filter { !PermissionManager.hasPermission(ctx, it.permission) }
            .map { it.permission }

        if (missing.isEmpty()) {
            viewModel.onDoneClicked(ctx)
            return
        }

        val promptable = missing.filterNot { isPermanentlyDeniedAfterPrompt(it) }
        if (promptable.isEmpty()) {
            openAppPermissionSettings()
            return
        }

        permissionQueue.clear()
        permissionQueue.addAll(promptable)
        isSequentialFlowRunning = true
        launchNextQueuedPermission()
    }

    private fun launchNextQueuedPermission() {
        val ctx = context ?: run {
            isSequentialFlowRunning = false
            return
        }

        while (permissionQueue.isNotEmpty()) {
            val next = permissionQueue.removeFirst()
            if (!PermissionManager.hasPermission(ctx, next)) {
                activePermission = next
                requestPermissionLauncher.launch(next)
                return
            }
        }

        isSequentialFlowRunning = false
        viewModel.refreshPermissions(ctx)
        val state = viewModel.uiState.value
        if (state.allGranted) {
            viewModel.onDoneClicked(ctx)
        } else {
            Toast.makeText(ctx, getString(R.string.permissions_denied_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isPermanentlyDeniedAfterPrompt(permission: String): Boolean {
        val act = activity ?: return false
        return askedPermissionsThisSession.contains(permission) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(act, permission)
    }

    private fun openAppPermissionSettings() {
        val ctx = context ?: return
        openedSettingsForRemaining = true
        Toast.makeText(ctx, getString(R.string.permissions_open_settings_toast), Toast.LENGTH_LONG).show()
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", ctx.packageName, null)
        )
        startActivity(intent)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.items.toList())

                    binding.btnAllowPermissions.text = if (state.allGranted) {
                        getString(R.string.permissions_btn_continue)
                    } else {
                        getString(R.string.permissions_btn_allow)
                    }

                    if (state.isCompleted) {
                        (activity as? MainActivity)?.navigateToHome()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): PermissionOnboardingFragment = PermissionOnboardingFragment()
    }
}
