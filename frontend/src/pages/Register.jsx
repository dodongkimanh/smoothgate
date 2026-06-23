import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'
import { register } from '../services/api'
import toast from 'react-hot-toast'
import { Eye, EyeOff, Mail, Lock, User, ArrowRight } from 'lucide-react'

export default function Register() {
  const navigate = useNavigate()
  const setAuth = useAuthStore((s) => s.setAuth)
  const [form, setForm] = useState({ fullName: '', email: '', password: '', confirmPassword: '' })
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)

  const updateForm = (key, value) => setForm({ ...form, [key]: value })

  const handleSubmit = async (e) => {
    e.preventDefault()
    if (form.password !== form.confirmPassword) {
      toast.error('Mật khẩu không khớp')
      return
    }

    setLoading(true)
    try {
      const { data } = await register({
        fullName: form.fullName,
        email: form.email,
        password: form.password,
      })
      setAuth(data.data.token, {
        email: data.data.email,
        fullName: data.data.fullName,
        role: data.data.role,
      })
      toast.success('Đăng ký thành công!')
      window.location.href = '/dashboard'
    } catch (err) {
      toast.error(err.response?.data?.message || 'Đăng ký thất bại')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 via-white to-cyan-50 flex items-center justify-center p-8">
      <div className="w-full max-w-md animate-fade-in">
        {/* Logo */}
        <div className="text-center mb-8">
          <div className="w-14 h-14 bg-gradient-to-br from-blue-500 to-cyan-400 rounded-2xl flex items-center justify-center mx-auto mb-3">
            <span className="text-white font-bold text-2xl">S</span>
          </div>
          <h1 className="text-xl font-bold text-gray-800">SmoothGate</h1>
        </div>

        <div className="bg-white rounded-2xl shadow-xl border border-gray-100 p-8">
          <div className="text-center mb-6">
            <h2 className="text-xl font-bold text-gray-800">Tạo tài khoản</h2>
            <p className="text-gray-500 mt-1 text-sm">
              Bắt đầu quản lý quảng cáo hiệu quả
            </p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">Họ và tên</label>
              <div className="relative">
                <User size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  type="text"
                  value={form.fullName}
                  onChange={(e) => updateForm('fullName', e.target.value)}
                  placeholder="Nguyễn Văn A"
                  className="input-field pl-10"
                  required
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">Email</label>
              <div className="relative">
                <Mail size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  type="email"
                  value={form.email}
                  onChange={(e) => updateForm('email', e.target.value)}
                  placeholder="name@company.com"
                  className="input-field pl-10"
                  required
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">Mật khẩu</label>
              <div className="relative">
                <Lock size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={form.password}
                  onChange={(e) => updateForm('password', e.target.value)}
                  placeholder="Tối thiểu 6 ký tự"
                  className="input-field pl-10 pr-10"
                  required
                  minLength={6}
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                >
                  {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">Xác nhận mật khẩu</label>
              <div className="relative">
                <Lock size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  type="password"
                  value={form.confirmPassword}
                  onChange={(e) => updateForm('confirmPassword', e.target.value)}
                  placeholder="Nhập lại mật khẩu"
                  className="input-field pl-10"
                  required
                />
              </div>
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full bg-blue-500 text-white py-3 rounded-xl font-medium text-sm
                         hover:bg-blue-600 transition-all duration-200 flex items-center justify-center gap-2
                         shadow-lg shadow-blue-500/30 hover:shadow-blue-500/40 disabled:opacity-50 mt-6"
            >
              {loading ? (
                <div className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin"></div>
              ) : (
                <>
                  Tạo tài khoản
                  <ArrowRight size={16} />
                </>
              )}
            </button>
          </form>
        </div>

        <p className="text-center text-sm text-gray-500 mt-6">
          Đã có tài khoản?{' '}
          <Link to="/login" className="text-blue-500 hover:text-blue-600 font-medium">
            Đăng nhập
          </Link>
        </p>
      </div>
    </div>
  )
}
