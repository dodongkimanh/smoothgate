import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { triggerAgentAnalysis } from '../services/api'
import toast from 'react-hot-toast'
import {
  Bot,
  Send,
  Zap,
  MessageCircle,
  Clock,
  CheckCircle,
  AlertCircle,
  Loader2,
} from 'lucide-react'

export default function Agent() {
  const [report, setReport] = useState(null)

  const analyzeMutation = useMutation({
    mutationFn: () => triggerAgentAnalysis(),
    onSuccess: (res) => {
      const data = res.data?.data
      setReport(data)
      toast.success('Phân tích hoàn tất!')
    },
    onError: (err) => {
      toast.error(err.response?.data?.message || 'Không thể chạy phân tích')
    },
  })

  return (
    <div className="max-w-4xl mx-auto space-y-6 animate-fade-in">
      <div>
        <h2 className="text-xl font-bold text-gray-800 flex items-center gap-2">
          <Bot size={24} className="text-blue-600" />
          Agent AI — Phân tích quảng cáo
        </h2>
        <p className="text-sm text-gray-500 mt-1">
          Claude AI tự động phân tích hiệu quả chiến dịch và gửi cảnh báo qua Telegram
        </p>
      </div>

      {/* Status Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <StatusCard
          icon={Zap}
          title="Model"
          value="Claude Sonnet 4.6"
          desc="Anthropic AI"
          color="text-purple-600 bg-purple-50"
        />
        <StatusCard
          icon={MessageCircle}
          title="Telegram"
          value="Đã cấu hình"
          desc="Gửi báo cáo tự động"
          color="text-blue-600 bg-blue-50"
        />
        <StatusCard
          icon={Clock}
          title="Tần suất"
          value="Mỗi 1 giờ"
          desc="Phân tích tự động"
          color="text-emerald-600 bg-emerald-50"
        />
      </div>

      {/* Config Guide */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
        <div className="px-6 py-4 bg-gray-50 border-b border-gray-100 flex items-center gap-2">
          <Send size={18} className="text-blue-600" />
          <h3 className="font-semibold text-gray-800">Cấu hình kết nối</h3>
        </div>

        <div className="px-6 py-5 space-y-4">
          <div className="bg-blue-50 border border-blue-100 rounded-lg p-4 text-sm text-blue-800">
            <p className="font-medium mb-2">Hướng dẫn thiết lập:</p>
            <ol className="list-decimal list-inside space-y-2 text-blue-700">
              <li>
                <strong>Anthropic API Key:</strong> Đăng ký tại{' '}
                <a href="https://console.anthropic.com" target="_blank" rel="noreferrer" className="underline font-medium">
                  console.anthropic.com
                </a>{' '}
                → Tạo API key
              </li>
              <li>
                <strong>Telegram Bot:</strong> Mở{' '}
                <a href="https://t.me/BotFather" target="_blank" rel="noreferrer" className="underline font-medium">
                  @BotFather
                </a>{' '}
                trên Telegram → Gửi <code>/newbot</code> → Lấy Bot Token
              </li>
              <li>
                <strong>Chat ID:</strong> Thêm bot vào group/chat → Gửi tin nhắn → Truy cập{' '}
                <code className="bg-blue-100 px-1 rounded">https://api.telegram.org/bot{'<TOKEN>'}/getUpdates</code>{' '}
                để lấy Chat ID
              </li>
              <li>
                <strong>Cấu hình env vars</strong> trên Render (hoặc .env):
              </li>
            </ol>
            <pre className="mt-3 bg-blue-100/60 rounded-lg p-3 text-xs font-mono overflow-x-auto">
{`ANTHROPIC_API_KEY=sk-ant-api03-...
TELEGRAM_BOT_TOKEN=123456:ABC-DEF...
TELEGRAM_CHAT_ID=-100123456789
AGENT_ENABLED=true`}
            </pre>
          </div>

          {/* How it works */}
          <div className="space-y-3">
            <h4 className="text-sm font-semibold text-gray-700">Cách hoạt động</h4>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <FeatureItem
                icon={Clock}
                title="Chạy định kỳ"
                desc="Mỗi 1 giờ tự động phân tích dữ liệu 7 ngày gần nhất"
              />
              <FeatureItem
                icon={Bot}
                title="Claude AI phân tích"
                desc="Đánh giá ROAS, CPO, chiến dịch lỗ, chi phí cao bất thường"
              />
              <FeatureItem
                icon={AlertCircle}
                title="Cảnh báo thông minh"
                desc="Phát hiện chiến dịch cần dừng hoặc tối ưu ngay"
              />
              <FeatureItem
                icon={MessageCircle}
                title="Gửi Telegram"
                desc="Báo cáo gửi trực tiếp vào group Telegram của bạn"
              />
            </div>
          </div>
        </div>
      </div>

      {/* Manual Trigger */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
        <div className="px-6 py-4 bg-gray-50 border-b border-gray-100 flex items-center gap-2">
          <Zap size={18} className="text-amber-600" />
          <h3 className="font-semibold text-gray-800">Chạy phân tích ngay</h3>
        </div>

        <div className="px-6 py-5 space-y-4">
          <p className="text-sm text-gray-600">
            Nhấn nút bên dưới để chạy phân tích ngay lập tức. Agent sẽ lấy dữ liệu 7 ngày gần nhất,
            phân tích bằng Claude AI, và gửi báo cáo qua Telegram (nếu đã cấu hình).
          </p>

          <button
            onClick={() => analyzeMutation.mutate()}
            disabled={analyzeMutation.isPending}
            className="inline-flex items-center gap-2 px-5 py-2.5 bg-blue-600 text-white rounded-xl font-medium text-sm
                       hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed
                       shadow-lg shadow-blue-500/20"
          >
            {analyzeMutation.isPending ? (
              <>
                <Loader2 size={18} className="animate-spin" />
                Đang phân tích...
              </>
            ) : (
              <>
                <Bot size={18} />
                Chạy phân tích ngay
              </>
            )}
          </button>

          {/* Report Result */}
          {report && (
            <div className="mt-4 rounded-xl border border-gray-200 bg-gray-50 overflow-hidden">
              <div className="px-4 py-3 bg-gray-100 border-b border-gray-200 flex items-center gap-2">
                <CheckCircle size={16} className="text-green-600" />
                <span className="text-sm font-semibold text-gray-700">Kết quả phân tích</span>
              </div>
              <div className="p-4 text-sm text-gray-700 whitespace-pre-wrap font-mono leading-relaxed max-h-[500px] overflow-y-auto">
                {report}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function StatusCard({ icon: Icon, title, value, desc, color }) {
  return (
    <div className="bg-white rounded-xl border border-gray-100 p-4 shadow-sm">
      <div className="flex items-center gap-3">
        <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${color}`}>
          <Icon size={20} />
        </div>
        <div>
          <div className="text-xs text-gray-500">{title}</div>
          <div className="text-sm font-bold text-gray-800">{value}</div>
          <div className="text-xs text-gray-400">{desc}</div>
        </div>
      </div>
    </div>
  )
}

function FeatureItem({ icon: Icon, title, desc }) {
  return (
    <div className="flex items-start gap-3 p-3 rounded-lg border border-gray-100 bg-gray-50/50">
      <div className="w-8 h-8 rounded-lg bg-blue-50 flex items-center justify-center shrink-0 mt-0.5">
        <Icon size={16} className="text-blue-600" />
      </div>
      <div>
        <div className="text-sm font-medium text-gray-800">{title}</div>
        <div className="text-xs text-gray-500 mt-0.5">{desc}</div>
      </div>
    </div>
  )
}
