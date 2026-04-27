package com.example.indoornavapp

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class SignUpActivity : AppCompatActivity() {

    private var passwordVisible = false
    private var confirmPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_sign_up)

        // Immersive status bar
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false

        val etName = findViewById<EditText>(R.id.et_name)
        val etEmail = findViewById<EditText>(R.id.et_email)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val etConfirmPassword = findViewById<EditText>(R.id.et_confirm_password)
        val ivTogglePassword = findViewById<ImageView>(R.id.iv_toggle_password)
        val ivToggleConfirmPassword = findViewById<ImageView>(R.id.iv_toggle_confirm_password)
        val btnSignUp = findViewById<View>(R.id.btn_sign_up)
        val btnBack = findViewById<View>(R.id.btn_back)
        val tvSignIn = findViewById<TextView>(R.id.tv_sign_in)

        // Toggle password visibility
        ivTogglePassword.setOnClickListener {
            passwordVisible = !passwordVisible
            etPassword.transformationMethod = if (passwordVisible)
                HideReturnsTransformationMethod.getInstance()
            else
                PasswordTransformationMethod.getInstance()
            etPassword.setSelection(etPassword.text.length)
            ivTogglePassword.setImageResource(if (passwordVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
        }

        // Toggle confirm password visibility
        ivToggleConfirmPassword.setOnClickListener {
            confirmPasswordVisible = !confirmPasswordVisible
            etConfirmPassword.transformationMethod = if (confirmPasswordVisible)
                HideReturnsTransformationMethod.getInstance()
            else
                PasswordTransformationMethod.getInstance()
            etConfirmPassword.setSelection(etConfirmPassword.text.length)
            ivToggleConfirmPassword.setImageResource(if (confirmPasswordVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
        }

        // Back button
        btnBack.setOnClickListener {
            finish()
        }

        // Sign up button
        btnSignUp.setOnClickListener {
            val name = etName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            // Validation
            when {
                name.isEmpty() -> {
                    Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                email.isEmpty() -> {
                    Toast.makeText(this, "Please enter your email", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                    Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                password.isEmpty() -> {
                    Toast.makeText(this, "Please enter a password", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                password.length < 6 -> {
                    Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                password != confirmPassword -> {
                    Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            // Demo registration: accept any valid input
            Toast.makeText(this, "Account created successfully!", Toast.LENGTH_SHORT).show()
            
            // Navigate to home
            startActivity(Intent(this, HomeActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }

        // Sign in link
        tvSignIn.setOnClickListener {
            finish()
        }
    }
}