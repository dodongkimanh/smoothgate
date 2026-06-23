import { useMutation, useQuery } from '@tanstack/react-query'
import { getMetaOAuthUrl, getMetaAppSettings } from '../services/api'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import toast from 'react-hot-toast'
import {
  Facebook,
  Link2,
  CheckCircle,
  ArrowRight,
  Shield,
  AlertCircle,
  Settings,
  Loader2,
} from 'lucide-react'

export default function ConnectAds() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const [errorMsg, setErrorMsg] = useState('')
  const [connecting, setConnecting] = useState(false)

  // Check if Meta App is configured
  const { data: metaSettings } = useQuery({
    queryKey: ['meta-app-settings'],
    queryFn: () => getMetaAppSettings().then(r => r.data?.data || {}),
  })
  const isMetaConfigured =
    metaSettings?.appId &&
    metaSettings.appId !== 'your_facebook_app_id' &&
    metaSettings.appId !== ''

  // Handle error redirected back from backend OAuth callback
  useEffect(() => {
    const error = searchParams.get('error')
    if (error) {
      setErrorMsg(decodeURIComponent(error))
      toast.error('Kết nối Meta Ads thất bại: ' + decodeURIComponent(error))
    }
  }, [searchParams])

  const handleConnectFacebook = async () => {
    // Guard: ensure Meta App credentials are configured before starting OAuth
    if (!isMetaConfigured) {
      toast.error('Vui lòng cấu hình Facebook App ID trước trong Cài đặt', { duration: 4000 })
      navigate('/settings')
      return
    }
    try {
      setConnecting(true)
      const { data } = await getMetaOAuthUrl()
      // Redirect browser to Meta's OAuth login page.
      // After user approves, Meta redirects back to our backend callback,
      // which then redirects here to /ad-accounts?connected=meta
      window.location.href = data.data.url
    } catch (err) {
      const msg = err.response?.data?.message || 'Không thể tạo URL kết nối Meta'
      toast.error(msg)
      setConnecting(false)
    }
  }

  const platforms = [
    {
      name: 'Facebook Ads',
      description: 'Kết nối tài khoản Meta Business để lấy dữ liệu quảng cáo Facebook & Instagram',
      icon: Facebook,
      color: 'blue',
      features: [
        'Đồng bộ chiến dịch tự động',
        'Theo dõi chi phí real-time',
        'Phân tích hiệu quả ROAS',
      ],
      onClick: handleConnectFacebook,
      available: true,
    },
  ]

  const colorMap = {
    blue: {
      bg: 'bg-blue-50',
      border: 'border-blue-200',
      icon: 'text-blue-600',
      button: 'bg-blue-500 hover:bg-blue-600',
    },
  }

  if (errorMsg) {
    return (
      <div className="max-w-2xl mx-auto mt-8">
        <div className="bg-white rounded-xl border border-red-200 p-6 flex items-start gap-4">
          <AlertCircle size={24} className="text-red-500 flex-shrink-0" />
          <div>
            <h3 className="font-semibold text-red-700">Kết nối Meta Ads thất bại</h3>
            <p className="text-sm text-red-600 mt-1">{errorMsg}</p>
            <button
              onClick={() => { setErrorMsg(''); window.history.replaceState({}, '', '/connect-ads') }}
              className="mt-4 btn-primary text-sm"
            >
              Thử lại
            </button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="max-w-4xl mx-auto space-y-8 animate-fade-in">
      {/* Header */}
      <div className="text-center">
        <div className="w-16 h-16 bg-blue-50 rounded-2xl flex items-center justify-center mx-auto mb-4">
          <Link2 size={28} className="text-blue-500" />
        </div>
        <h2 className="text-2xl font-bold text-gray-800">Kết nối tài khoản quảng cáo</h2>
        <p className="text-gray-500 mt-2 max-w-lg mx-auto">
          Liên kết tài khoản quảng cáo để tự động đồng bộ dữ liệu chiến dịch, 
          theo dõi chi phí và phân tích hiệu quả quảng cáo
        </p>
      </div>

      {/* Security Note */}
      <div className="flex items-start gap-3 p-4 bg-green-50 border border-green-100 rounded-xl">
        <Shield size={20} className="text-green-600 mt-0.5 flex-shrink-0" />
        <div>
          <div className="font-medium text-green-800 text-sm">Bảo mật tuyệt đối</div>
          <div className="text-xs text-green-600 mt-0.5">
            Token được mã hóa AES-256 trước khi lưu trữ. Chúng tôi chỉ yêu cầu quyền đọc dữ liệu quảng cáo.
          </div>
        </div>
      </div>

      {/* Warning: Meta App not configured */}
      {metaSettings && !isMetaConfigured && (
        <div className="flex items-start gap-3 p-4 bg-amber-50 border border-amber-200 rounded-xl">
          <AlertCircle size={20} className="text-amber-600 mt-0.5 flex-shrink-0" />
          <div className="flex-1">
            <p className="font-medium text-amber-800 text-sm">
              Cần cấu hình Facebook App trước khi kết nối
            </p>
            <p className="text-xs text-amber-700 mt-0.5">
              Vào <strong>Cài đặt → Meta Ads</strong> để nhập App ID và App Secret từ tài khoản Facebook Developer của bạn.
            </p>
          </div>
          <button
            onClick={() => navigate('/settings')}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-amber-600 text-white rounded-lg text-xs font-medium hover:bg-amber-700 flex-shrink-0"
          >
            <Settings size={14} />
            Cài đặt
          </button>
        </div>
      )}

      {/* Platform Cards */}
      <div className="space-y-4">
        {platforms.map((platform, index) => {
          const Icon = platform.icon
          const colors = colorMap[platform.color]
          return (
            <div
              key={index}
              className={`bg-white rounded-xl border ${colors.border} overflow-hidden
                         hover:shadow-lg transition-all duration-300 animate-fade-in`}
              style={{ animationDelay: `${index * 100}ms` }}
            >
              <div className="p-6">
                <div className="flex items-start justify-between">
                  <div className="flex items-start gap-4">
                    <div className={`w-14 h-14 ${colors.bg} rounded-xl flex items-center justify-center flex-shrink-0`}>
                      <Icon size={28} className={colors.icon} />
                    </div>
                    <div className="flex-1">
                      <div className="flex items-center gap-2">
                        <h3 className="text-lg font-semibold text-gray-800">{platform.name}</h3>
                        {!platform.available && (
                          <span className="px-2 py-0.5 bg-gray-100 text-gray-500 rounded-full text-xs font-medium">
                            Sắp có
                          </span>
                        )}
                      </div>
                      <p className="text-sm text-gray-500 mt-1">{platform.description}</p>
                      <div className="flex flex-wrap gap-2 mt-3">
                        {platform.features.map((feature, i) => (
                          <div key={i} className="flex items-center gap-1.5 text-xs text-gray-600">
                            <CheckCircle size={14} className="text-green-500" />
                            {feature}
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                  <button
                    onClick={platform.onClick}
                    disabled={!platform.available || (platform.name === 'Facebook Ads' && connecting)}
                    className={`flex items-center gap-2 px-5 py-2.5 rounded-xl text-white font-medium text-sm
                               transition-all duration-200 ${
                      platform.available
                        ? `${colors.button} shadow-sm hover:shadow-md`
                        : 'bg-gray-300 cursor-not-allowed'
                    }`}
                  >
                    {platform.name === 'Facebook Ads' && connecting ? (
                      <><Loader2 size={16} className="animate-spin" /> Đang chuyển hướng...</>
                    ) : platform.available ? (
                      <>{isMetaConfigured || platform.name !== 'Facebook Ads' ? 'Kết nối' : 'Cấu hình trước'} <ArrowRight size={16} /></>
                    ) : 'Sắp có'}
                  </button>
                </div>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
