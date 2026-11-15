const MAX_SIZE = 1024
const QUALITY = 0.82

export function dataUrlToFile(dataUrl, baseName = 'source') {
  if (dataUrl instanceof File) return dataUrl
  if (dataUrl instanceof Blob) {
    const mediaType = (dataUrl.type || 'image/jpeg').toLowerCase()
    return new File([dataUrl], `${baseName}.${extensionFor(mediaType)}`, { type: mediaType })
  }

  const value = String(dataUrl || '').trim()
  const match = /^data:([^;,\s]+)(?:;[^,]*)?,([\s\S]*)$/i.exec(value)
  if (!match || !match[1].toLowerCase().startsWith('image/')) {
    throw new Error('参考图片数据无效，请重新上传')
  }

  const mediaType = match[1].toLowerCase()
  const encoded = match[2]
  let bytes
  try {
    if (/;base64/i.test(value.slice(0, value.indexOf(',')))) {
      const binary = atob(encoded.replace(/\s/g, ''))
      bytes = new Uint8Array(binary.length)
      for (let i = 0; i < binary.length; i += 1) {
        bytes[i] = binary.charCodeAt(i)
      }
    } else {
      const decoded = decodeURIComponent(encoded)
      bytes = new TextEncoder().encode(decoded)
    }
  } catch {
    throw new Error('参考图片数据无效，请重新上传')
  }

  if (!bytes.length) throw new Error('参考图片数据为空，请重新上传')
  return new File([bytes], `${baseName}.${extensionFor(mediaType)}`, { type: mediaType })
}

function extensionFor(mediaType) {
  if (mediaType === 'image/jpeg' || mediaType === 'image/jpg') return 'jpg'
  if (mediaType === 'image/svg+xml') return 'svg'
  return mediaType.split('/')[1]?.split('+')[0] || 'img'
}

export function compressImage(file) {
  return new Promise((resolve, reject) => {
    if (!file) {
      reject(new Error('未获取到图片数据'))
      return
    }

    const reader = new FileReader()
    reader.onload = (e) => {
      const img = new Image()
      img.onload = () => {
        let { width, height } = img
        if (!width || !height) {
          reject(new Error('无法读取图片尺寸'))
          return
        }

        if (width > MAX_SIZE || height > MAX_SIZE) {
          if (width >= height) {
            height = Math.round((height / width) * MAX_SIZE)
            width = MAX_SIZE
          } else {
            width = Math.round((width / height) * MAX_SIZE)
            height = MAX_SIZE
          }
        }

        const canvas = document.createElement('canvas')
        canvas.width = width
        canvas.height = height
        const ctx = canvas.getContext('2d')
        if (!ctx) {
          reject(new Error('浏览器不支持图片处理'))
          return
        }

        ctx.drawImage(img, 0, 0, width, height)
        resolve(canvas.toDataURL('image/jpeg', QUALITY))
      }
      img.onerror = () => reject(new Error('图片解码失败'))
      img.src = e.target?.result
    }
    reader.onerror = () => reject(new Error('图片读取失败'))
    reader.readAsDataURL(file)
  })
}
