import { useAuthStore } from '../store/authStore'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getMetaAppSettings, saveMetaAppSettings, changePassword } from '../services/api'
import { useState, useEffect } from 'react'
import toast from 'react-hot-toast'
import {
  User,
  Bell,
  Shield,
  Database,
  LogOut,
  Facebook,
  Eye,
  EyeOff,
  CheckCircle,
  AlertCircle,
  ExternalLink,
} from 'lucide-react'
import { useNavigate } from 'react-router-dom'

export default function Settings() {
  const { user, logout } = useAuthStore()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const defaultRedirectUri = import.meta.env.DEV
    ? 'http://localhost:8080/api/integrations/meta/oauth/callback'
    : 'https://YOUR_BACKEND_URL/api/integrations/meta/oauth/callback'

  const [metaForm, setMetaForm] = useState({
    appId: '',
    appSecret: '',
    redirectUri: defaultRedirectUri,
  })
  const [showSecret, setShowSecret] = useState(false)
  const [metaFormDirty, setMetaFormDirty] = useState(false)
  const [passwordForm, setPasswordForm] = useState({
    currentPassword: '',
    newPassword: '',
    confirmPassword: '',
  })
  const [showPassword, setShowPassword] = useState({
    current: false,
    next: false,
    confirm: false,
  })

  const { data: metaSettings } = useQuery({
    queryKey: ['meta-app-settings'],
    queryFn: () => getMetaAppSettings().then(r => r.data?.data || {}),
  })

  useEffect(() => {
    if (metaSettings && !metaFormDirty) {
      setMetaForm({
        appId: metaSettings.appId || '',
        appSecret: '',
        redirectUri: metaSettings.redirectUri || defaultRedirectUri,
      })
    }
  }, [metaSettings, metaFormDirty])

  const saveMeta = useMutation({
    mutationFn: () => saveMetaAppSettings(metaForm.appId, metaForm.appSecret, metaForm.redirectUri),
    onSuccess: () => {
      toast.success('Đã lưu cấu hình Meta Ads')
      queryClient.invalidateQueries({ queryKey: ['meta-app-settings'] })
      setMetaFormDirty(false)
      setMetaForm(prev => ({ ...prev, appSecret: '' }))
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Lưu thất bại'),
  })

  const changePasswordMutation = useMutation({
    mutationFn: () => changePassword(
      passwordForm.currentPassword,
      passwordForm.newPassword,
      passwordForm.confirmPassword
    ),
    onSuccess: () => {
      toast.success('Đổi mật khẩu thành công')
      setPasswordForm({ currentPassword: '', newPassword: '', confirmPassword: '' })
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Không thể đổi mật khẩu'),
  })

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  const isMetaConfigured =
    metaSettings?.appId &&
    metaSettings.appId !== 'your_facebook_app_id' &&
    metaSettings.appId !== ''

  const staticSections = [
    {
      title: 'Tài khoản',
      icon: User,
      items: [
        { label: 'Email', value: user?.email || 'user@example.com' },
        { label: 'Họ tên', value: user?.fullName || 'User' },
        { label: 'Vai trò', value: user?.role || 'ADVERTISER' },
      ],
    },
    {
      title: 'Thông báo',
      icon: Bell,
      items: [
        { label: 'Email thông báo', value: 'Bật', toggle: true },
        { label: 'Thông báo đồng bộ', value: 'Bật', toggle: true },
      ],
    },
    {
      title: 'Bảo mật',
      icon: Shield,
      items: [
        { label: 'Xác thực 2 bước', value: 'Tắt', toggle: true },
        { label: 'Đổi mật khẩu', value: 'Có sẵn bên dưới' },
      ],
    },
    {
      title: 'Đồng bộ dữ liệu',
      icon: Database,
      items: [
        { label: 'Tần suất đồng bộ quảng cáo', value: 'Mỗi 30 phút' },
        { label: 'Tần suất đồng bộ đơn hàng', value: 'Mỗi 5 phút' },
        { label: 'Attribution window', value: '24 giờ' },
      ],
    },
  ]

  return (
    <div className="max-w-3xl mx-auto space-y-6 animate-fade-in">
      <div>
        <h2 className="text-xl font-bold text-gray-800">Cài đặt</h2>
        <p className="text-sm text-gray-500 mt-1">Quản lý tài khoản và cấu hình hệ thống</p>
      </div>

      {/* Meta Ads App Configuration */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
        <div className="px-6 py-4 bg-gray-50 border-b border-gray-100 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Facebook size={18} className="text-blue-600" />
            <h3 className="font-semibold text-gray-800">Meta Ads — Cấu hình ứng dụng</h3>
          </div>
          {isMetaConfigured ? (
            <span className="flex items-center gap-1 text-xs text-green-600 font-medium">
              <CheckCircle size={14} /> Đã cấu hình
            </span>
          ) : (
            <span className="flex items-center gap-1 text-xs text-amber-600 font-medium">
              <AlertCircle size={14} /> Chưa cấu hình
            </span>
          )}
        </div>

        <div className="px-6 py-5 space-y-4">
          <div className="bg-blue-50 border border-blue-100 rounded-lg p-4 text-sm text-blue-800">
            <p className="font-medium mb-2">Hướng dẫn lấy thông tin:</p>
            <ol className="list-decimal list-inside space-y-1 text-blue-700">
              <li>
                Vào{' '}
                <a
                  href="https://developers.facebook.com/apps"
                  target="_blank"
                  rel="noreferrer"
                  className="underline font-medium inline-flex items-center gap-0.5"
                >
                  developers.facebook.com/apps <ExternalLink size={11} />
                </a>{' '}
                → Tạo ứng dụng mới (loại <strong>Business</strong>)
              </li>
              <li>Vào <strong>Settings → Basic</strong> → Sao chép <strong>App ID</strong> và <strong>App Secret</strong></li>
              <li>
                Vào <strong>Facebook Login → Settings</strong> → Thêm URI bên dưới vào{' '}
                <strong>Valid OAuth Redirect URIs</strong>
              </li>
              <li>Bật sản phẩm <strong>Marketing API</strong> trong ứng dụng</li>
            </ol>
          </div>

          <div className="space-y-3">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">App ID</label>
              <input
                type="text"
                value={metaForm.appId}
                onChange={e => { setMetaForm(f => ({ ...f, appId: e.target.value })); setMetaFormDirty(true) }}
                placeholder="Nhập Facebook App ID (vd: 1234567890123456)"
                className="w-full px-3 py-2 border border-gray-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                App Secret
                {metaSettings?.appSecretSet === 'true' && (
                  <span className="ml-2 text-xs text-green-600 font-normal">
                    (đã lưu — để trống nếu không muốn thay đổi)
                  </span>
                )}
              </label>
              <div className="relative">
                <input
                  type={showSecret ? 'text' : 'password'}
                  value={metaForm.appSecret}
                  onChange={e => { setMetaForm(f => ({ ...f, appSecret: e.target.value })); setMetaFormDirty(true) }}
                  placeholder={metaSettings?.appSecretSet === 'true' ? '••••••••••••••••' : 'Nhập Facebook App Secret'}
                  className="w-full px-3 py-2 border border-gray-200 rounded-lg text-sm pr-10 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                <button
                  type="button"
                  onClick={() => setShowSecret(s => !s)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                >
                  {showSecret ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Redirect URI{' '}
                <span className="text-xs text-gray-400 font-normal">
                  (phải thêm chính xác URI này vào Facebook App settings)
                </span>
              </label>
              <input
                type="text"
                value={metaForm.redirectUri}
                readOnly
                disabled
                className="w-full px-3 py-2 border border-gray-200 rounded-lg text-sm font-mono bg-gray-50 text-gray-500 cursor-not-allowed select-all"
              />
            </div>
          </div>

          <div className="flex items-center gap-3">
            <button
              onClick={() => saveMeta.mutate()}
              disabled={saveMeta.isPending || !metaFormDirty}
              className="btn-primary disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {saveMeta.isPending ? 'Đang lưu...' : 'Lưu cấu hình'}
            </button>
            {isMetaConfigured && (
              <a href="/connect-ads" className="text-sm text-blue-600 hover:underline">
                → Đến trang kết nối tài khoản quảng cáo
              </a>
            )}
          </div>
        </div>
      </div>

      {/* Static sections */}
      {staticSections.map((section, index) => {
        const Icon = section.icon
        return (
          <div key={index} className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
            <div className="px-6 py-4 bg-gray-50 border-b border-gray-100 flex items-center gap-2">
              <Icon size={18} className="text-gray-600" />
              <h3 className="font-semibold text-gray-800">{section.title}</h3>
            </div>
            <div className="divide-y divide-gray-50">
              {section.items.map((item, i) => (
                <div key={i} className="px-6 py-4 flex items-center justify-between">
                  <span className="text-sm text-gray-600">{item.label}</span>
                  {item.toggle ? (
                    <button className="relative w-11 h-6 bg-blue-500 rounded-full transition-colors">
                      <span className="absolute right-1 top-1 w-4 h-4 bg-white rounded-full shadow transition-transform" />
                    </button>
                  ) : (
                    <span className="text-sm font-medium text-gray-800">{item.value}</span>
                  )}
                </div>
              ))}
            </div>
          </div>
        )
      })}

      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
        <div className="px-6 py-4 bg-gray-50 border-b border-gray-100 flex items-center gap-2">
          <Shield size={18} className="text-gray-600" />
          <h3 className="font-semibold text-gray-800">Đổi mật khẩu</h3>
        </div>
        <div className="px-6 py-5 space-y-4">
          <PasswordField
            label="Mật khẩu hiện tại"
            value={passwordForm.currentPassword}
            onChange={(v) => setPasswordForm((f) => ({ ...f, currentPassword: v }))}
            visible={showPassword.current}
            onToggle={() => setShowPassword((s) => ({ ...s, current: !s.current }))}
          />
          <PasswordField
            label="Mật khẩu mới"
            value={passwordForm.newPassword}
            onChange={(v) => setPasswordForm((f) => ({ ...f, newPassword: v }))}
            visible={showPassword.next}
            onToggle={() => setShowPassword((s) => ({ ...s, next: !s.next }))}
          />
          <PasswordField
            label="Xác nhận mật khẩu mới"
            value={passwordForm.confirmPassword}
            onChange={(v) => setPasswordForm((f) => ({ ...f, confirmPassword: v }))}
            visible={showPassword.confirm}
            onToggle={() => setShowPassword((s) => ({ ...s, confirm: !s.confirm }))}
          />

          <button
            onClick={() => changePasswordMutation.mutate()}
            disabled={changePasswordMutation.isPending || !passwordForm.currentPassword || !passwordForm.newPassword || !passwordForm.confirmPassword}
            className="btn-primary disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {changePasswordMutation.isPending ? 'Đang đổi mật khẩu...' : 'Đổi mật khẩu'}
          </button>
        </div>
      </div>

      <button
        onClick={handleLogout}
        className="w-full bg-white rounded-xl shadow-sm border border-red-100 p-4
                   flex items-center justify-center gap-2 text-red-500 font-medium
                   hover:bg-red-50 transition-colors"
      >
        <LogOut size={18} />
        Đăng xuất
      </button>
    </div>
  )
}

function PasswordField({ label, value, onChange, visible, onToggle }) {
  return (
    <div>
      <label className="block text-sm font-medium text-gray-700 mb-1">{label}</label>
      <div className="relative">
        <input
          type={visible ? 'text' : 'password'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="w-full px-3 py-2 border border-gray-200 rounded-lg text-sm pr-10 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <button
          type="button"
          onClick={onToggle}
          className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
        >
          {visible ? <EyeOff size={16} /> : <Eye size={16} />}
        </button>
      </div>
    </div>
  )
}
