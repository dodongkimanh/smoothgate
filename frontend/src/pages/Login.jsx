import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'
import { login } from '../services/api'
import toast from 'react-hot-toast'
import { Eye, EyeOff, Mail, Lock, ArrowRight } from 'lucide-react'
import logo from '../assets/smoothgate-logo.svg'

export default function Login() {
  const navigate = useNavigate()
  const setAuth = useAuthStore((s) => s.setAuth)
  const logout = useAuthStore((s) => s.logout)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)

  const isDev = import.meta.env.DEV

  const handleDevLogin = () => {
    logout()
    setAuth('dev-mock-token', {
      email: 'admin@dev.local',
      fullName: 'Admin (Dev)',
      role: 'ADMIN',
    }, 1)
    toast.success('Dev mode — đăng nhập mock')
    navigate('/dashboard', { replace: true })
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setLoading(true)

    try {
      logout()
      const { data } = await login({ email, password })
      setAuth(data.data.token, {
        email: data.data.email,
        fullName: data.data.fullName,
        role: data.data.role,
      }, data.data.tenantId)
      toast.success('Đăng nhập thành công!')
      navigate('/dashboard', { replace: true })
    } catch (err) {
      const status = err.response?.status
      const msg = err.response?.data?.message
      if (status === 401 || status === 403) {
        toast.error('Email hoặc mật khẩu không đúng')
      } else if (status === 400) {
        toast.error(msg || 'Thông tin đăng nhập không hợp lệ')
      } else if (!err.response) {
        toast.error('Không thể kết nối server. Kiểm tra backend đã chạy chưa.')
      } else {
        toast.error(msg || 'Đăng nhập thất bại')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 via-white to-cyan-50 flex">
      {/* Left Panel - Branding */}
      <div className="hidden lg:flex lg:w-1/2 bg-gradient-to-br from-blue-600 to-blue-800 p-12 flex-col justify-between relative overflow-hidden">
        {/* Background Pattern */}
        <div className="absolute inset-0 opacity-10">
          <div className="absolute top-20 -left-20 w-80 h-80 bg-white rounded-full"></div>
          <div className="absolute bottom-20 right-10 w-60 h-60 bg-white rounded-full"></div>
          <div className="absolute top-1/2 left-1/3 w-40 h-40 bg-white rounded-full"></div>
        </div>

        <div className="relative z-10">
          <div className="flex items-center gap-3 mb-2">
            <img src={logo} alt="SmoothGate" className="w-12 h-12 rounded-xl bg-white/15 p-1.5" />
            <span className="text-white text-2xl font-bold">SmoothGate</span>
          </div>
          <p className="text-blue-200 text-sm mt-1">Advertising Management Platform</p>
        </div>

        <div className="relative z-10 space-y-8">
          <div>
            <h2 className="text-3xl font-bold text-white leading-tight">
              Quản lý quảng cáo<br />
              thông minh hơn
            </h2>
            <p className="text-blue-200 mt-4 text-sm leading-relaxed max-w-md">
              Kết nối tất cả tài khoản quảng cáo, đồng bộ đơn hàng từ Poscake, 
              và phân tích hiệu quả quảng cáo với ROAS, CPA trong một nền tảng duy nhất.
            </p>
          </div>

          <div className="grid grid-cols-2 gap-4">
            {[
              { label: 'Kết nối Meta Ads', value: 'Sẵn sàng' },
              { label: 'Kết nối Pancake', value: 'Sẵn sàng' },
              { label: 'Đồng bộ dữ liệu', value: 'Real-time' },
              { label: 'Attribution', value: 'Đa kênh' },
            ].map((stat) => (
              <div key={stat.label} className="bg-white/10 backdrop-blur-sm rounded-xl p-4">
                <div className="text-lg font-bold text-white">{stat.value}</div>
                <div className="text-xs text-blue-200 mt-1">{stat.label}</div>
              </div>
            ))}
          </div>
        </div>

        <div className="relative z-10 text-xs text-blue-300">
          © 2026 SmoothGate. All rights reserved.
        </div>
      </div>

      {/* Right Panel - Form */}
      <div className="flex-1 flex items-center justify-center p-8">
        <div className="w-full max-w-md animate-fade-in">
          {/* Mobile Logo */}
          <div className="lg:hidden text-center mb-8">
            <img src={logo} alt="SmoothGate" className="w-14 h-14 rounded-2xl mx-auto mb-3" />
            <h1 className="text-xl font-bold text-gray-800">SmoothGate</h1>
          </div>

          <div className="text-center mb-8">
            <h2 className="text-2xl font-bold text-gray-800">Đăng nhập</h2>
            <p className="text-gray-500 mt-2 text-sm">
              Nhập thông tin để truy cập hệ thống quản lý quảng cáo
            </p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">Email</label>
              <div className="relative">
                <Mail size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
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
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••"
                  className="input-field pl-10 pr-10"
                  required
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

            <div className="flex items-center justify-between">
              <label className="flex items-center gap-2">
                <input type="checkbox" className="w-4 h-4 rounded border-gray-300 text-blue-500" />
                <span className="text-sm text-gray-600">Ghi nhớ đăng nhập</span>
              </label>
              <a href="#" className="text-sm text-blue-500 hover:text-blue-600 font-medium">
                Quên mật khẩu?
              </a>
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full bg-blue-500 text-white py-3 rounded-xl font-medium text-sm
                         hover:bg-blue-600 transition-all duration-200 flex items-center justify-center gap-2
                         shadow-lg shadow-blue-500/30 hover:shadow-blue-500/40 disabled:opacity-50"
            >
              {loading ? (
                <div className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin"></div>
              ) : (
                <>
                  Đăng nhập
                  <ArrowRight size={16} />
                </>
              )}
            </button>
          </form>

          {isDev && (
            <button
              onClick={handleDevLogin}
              className="w-full mt-4 py-2.5 border-2 border-dashed border-amber-300 text-amber-600 rounded-xl text-sm font-medium hover:bg-amber-50 transition-colors"
            >
              Dev Mode — Vào Dashboard không cần backend
            </button>
          )}
      </div>
    </div>
  </div>
  )
}
