package com.msi.openchat.feature.permission

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.MainActivity
import com.example.databinding.FragmentPermissionOnboardingBinding
import kotlinx.coroutines.launch

class PermissionOnboardingFragment : Fragment() {

    private var _binding: FragmentPermissionOnboardingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PermissionOnboardingViewModel by viewModels()

    private var pendingPermission: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        pendingPermission?.let { perm ->
            viewModel.onPermissionResult(perm, isGranted)
            if (!isGranted) {
                Toast.makeText(
                    requireContext(),
                    "Permission denied. You can grant it later contextually or in App Settings.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        pendingPermission = null
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

        setupBackPressHandling()
        setupRecyclerView()
        setupButtons()
        observeViewModel()

        viewModel.loadPermissions(requireContext())
    }

    override fun onResume() {
        super.onResume()
        // Refresh grant statuses in case the user toggled permissions in system settings
        viewModel.refreshPermissions(requireContext())
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
            if (item.isGranted) {
                Toast.makeText(
                    requireContext(),
                    "${item.title} is already enabled.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                pendingPermission = item.permission
                requestPermissionLauncher.launch(item.permission)
            }
        }

        binding.recyclerPermissions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PermissionOnboardingFragment.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupButtons() {
        binding.btnDonePermissions.setOnClickListener {
            viewModel.onDoneClicked(requireContext())
        }

        binding.btnSkipPermissions.setOnClickListener {
            viewModel.onSkipClicked(requireContext())
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.items.toList())

                    // Enable Done once handled (or when all granted)
                    binding.btnDonePermissions.isEnabled = state.isDoneEnabled
                    binding.btnDonePermissions.alpha = if (state.isDoneEnabled) 1.0f else 0.5f

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
