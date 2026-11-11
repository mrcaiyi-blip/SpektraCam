import AVFoundation
import CoreImage
import MetalKit
import SwiftUI

final class RawIspLutPreviewRenderer: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, MTKViewDelegate {
    let frameQueue = DispatchQueue(label: "raw-isp.preview.frames", qos: .userInteractive)
    private let lutQueue = DispatchQueue(label: "raw-isp.preview.lut", qos: .userInitiated)
    let device = MTLCreateSystemDefaultDevice()
    private let lock = NSLock()
    private var latestBuffer: CVPixelBuffer?
    private var latestMirrored = false
    private var focusGeometry: (size: CGSize, zoom: CGFloat, mirrored: Bool)?
    private var lutTexture: MTLTexture?
    private var filmMode = false
    private var zoom: CGFloat = 1
    private var selectionGeneration = 0
    private var pipeline: MTLRenderPipelineState?
    private var commands: MTLCommandQueue?
    private var ciContext: CIContext?
    private var sourceTexture: MTLTexture?
    private let inFlight = DispatchSemaphore(value: 2)
    private let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    var onError: ((String) -> Void)?

    override init() {
        super.init()
        guard let device else { return }
        do {
            let library = try device.makeLibrary(source: Self.shader, options: nil)
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = library.makeFunction(name: "vertexMain")
            descriptor.fragmentFunction = library.makeFunction(name: "fragmentMain")
            descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm
            pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
            commands = device.makeCommandQueue()
            ciContext = CIContext(mtlDevice: device, options: [.workingColorSpace: NSNull(), .cacheIntermediates: false])
        } catch { NSLog("[RawIsp] Preview initialization failed: %@", String(describing: error)) }
    }

    var available: Bool { pipeline != nil && commands != nil }

    func setZoom(_ value: Double) {
        lock.lock(); zoom = CGFloat(max(1, min(4, value))); lock.unlock()
    }

    func setFilm(_ look: RawIspFilmLook) {
        let path = look.previewAssetPath
        lock.lock()
        selectionGeneration += 1
        let generation = selectionGeneration
        lutTexture = nil; filmMode = false
        lock.unlock()
        lutQueue.async { [weak self] in
            guard let self, let device = self.device else { return }
            do {
                var texture: MTLTexture?
                if !path.isEmpty {
                    guard let root = Bundle.main.resourceURL else { throw RawIspLutCube.InvalidLut.data }
                    let cube = try RawIspLutCube.load(path: path, root: root)
                    let descriptor = MTLTextureDescriptor()
                    descriptor.textureType = .type3D; descriptor.pixelFormat = .rgba16Float
                    descriptor.width = cube.size; descriptor.height = cube.size; descriptor.depth = cube.size
                    descriptor.storageMode = .shared; descriptor.usage = .shaderRead
                    guard let created = device.makeTexture(descriptor: descriptor) else { throw RawIspLutCube.InvalidLut.data }
                    let values = cube.rgba.map { Float16($0).bitPattern }
                    values.withUnsafeBytes { bytes in
                        created.replace(region: MTLRegionMake3D(0, 0, 0, cube.size, cube.size, cube.size),
                            mipmapLevel: 0, slice: 0, withBytes: bytes.baseAddress!,
                            bytesPerRow: cube.size * 8, bytesPerImage: cube.size * cube.size * 8)
                    }
                    texture = created
                }
                self.lock.lock()
                if generation == self.selectionGeneration { self.lutTexture = texture; self.filmMode = true }
                self.lock.unlock()
            } catch {
                self.lock.lock(); let current = generation == self.selectionGeneration; self.lock.unlock()
                if current { DispatchQueue.main.async { self.onError?(String(describing: error)) } }
            }
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lock.lock()
        latestBuffer = buffer
        latestMirrored = connection.isVideoMirrored
        lock.unlock()
    }

    func resetFocusGeometry() {
        lock.lock()
        latestBuffer = nil
        focusGeometry = nil
        lock.unlock()
    }

    func focusPoint(at point: CGPoint, in bounds: CGRect) -> CGPoint? {
        lock.lock()
        let geometry = focusGeometry
        lock.unlock()
        guard let geometry, bounds.width > 0, bounds.height > 0 else { return nil }
        let image = AVMakeRect(aspectRatio: geometry.size, insideRect: bounds)
        guard image.contains(point) else { return nil }
        let x = ((point.x - image.minX) / image.width - 0.5) / geometry.zoom + 0.5
        let y = ((point.y - image.minY) / image.height - 0.5) / geometry.zoom + 0.5
        // Undo the preview's clockwise rotation and any mirror applied to its source buffer.
        return CGPoint(x: geometry.mirrored ? 1 - y : y, y: 1 - x)
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        guard inFlight.wait(timeout: .now()) == .success else { return }
        var submitted = false
        defer { if !submitted { inFlight.signal() } }
        lock.lock()
        let buffer = latestBuffer, lut = lutTexture, isFilm = filmMode, currentZoom = zoom
        let mirrored = latestMirrored
        lock.unlock()
        guard let buffer, let device, let pipeline, let ciContext,
              let command = commands?.makeCommandBuffer(), let drawable = view.currentDrawable,
              let pass = view.currentRenderPassDescriptor else { return }
        let width = drawable.texture.width, height = drawable.texture.height
        if sourceTexture?.width != width || sourceTexture?.height != height {
            let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm,
                width: width, height: height, mipmapped: false)
            descriptor.usage = [.shaderRead, .shaderWrite, .renderTarget]
            sourceTexture = device.makeTexture(descriptor: descriptor)
        }
        guard let sourceTexture else { return }
        // The enclosing view rotates the live preview from these portrait coordinates.
        let source = CIImage(cvPixelBuffer: buffer).oriented(.right)
        let crop = CGRect(x: source.extent.midX-source.extent.width/currentZoom/2,
                          y: source.extent.midY-source.extent.height/currentZoom/2,
                          width: source.extent.width/currentZoom, height: source.extent.height/currentZoom)
        let image = source.cropped(to: crop).transformed(by: CGAffineTransform(translationX: -crop.minX, y: -crop.minY))
        let scale = min(CGFloat(width) / image.extent.width, CGFloat(height) / image.extent.height)
        let fitted = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            .transformed(by: CGAffineTransform(translationX: (CGFloat(width) - image.extent.width * scale) / 2,
                                             y: (CGFloat(height) - image.extent.height * scale) / 2))
        let bounds = CGRect(x: 0, y: 0, width: width, height: height)
        let background = CIImage(color: .black).cropped(to: bounds)
        ciContext.render(fitted.composited(over: background), to: sourceTexture, commandBuffer: command,
                         bounds: bounds, colorSpace: colorSpace)
        guard let encoder = command.makeRenderCommandEncoder(descriptor: pass) else { return }
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(sourceTexture, index: 0)
        encoder.setFragmentTexture(lut, index: 1)
        var enabled: UInt32 = lut == nil ? 0 : 1
        encoder.setFragmentBytes(&enabled, length: MemoryLayout<UInt32>.size, index: 0)
        var film: UInt32 = isFilm ? 1 : 0
        encoder.setFragmentBytes(&film, length: MemoryLayout<UInt32>.size, index: 1)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        command.addCompletedHandler { [weak self] command in
            self?.inFlight.signal()
            if command.status == .error {
                DispatchQueue.main.async { self?.onError?(command.error?.localizedDescription ?? "Metal preview failed") }
            }
        }
        command.present(drawable); command.commit(); submitted = true
        lock.lock()
        focusGeometry = (source.extent.size, currentZoom, mirrored)
        lock.unlock()
    }

    private static let shader = """
    #include <metal_stdlib>
    using namespace metal;
    struct Vertex { float4 position [[position]]; float2 uv; };
    vertex Vertex vertexMain(uint id [[vertex_id]]) {
        float2 p=float2((id<<1)&2,id&2);
        // Core Image writes Metal textures in its bottom-left image convention.
        // Sample that texture with the same coordinates; a UIKit-style Y flip
        // here would invert the video vertically a second time.
        return {float4(p*2.0-1.0,0,1),p};
    }
    float3 srgb_decode(float3 c) { return select(pow((c+0.055)/1.055,float3(2.4)),c/12.92,c<=0.04045); }
    float3 srgb_shape(float3 c) { c=max(c,0.0); return clamp(select(1.055*pow(c,float3(1.0/2.4))-0.055,c*12.92,c<=0.0031308),0.0,1.0); }
    fragment float4 fragmentMain(Vertex v [[stage_in]], texture2d<float> camera [[texture(0)]],
        texture3d<float> lut [[texture(1)]], constant uint& enabled [[buffer(0)]],
        constant uint& film [[buffer(1)]]) {
        constexpr sampler linearSampler(coord::normalized,address::clamp_to_edge,filter::linear);
        float3 c=clamp(camera.sample(linearSampler,v.uv).rgb,0.0,1.0),g=c;
        if(enabled!=0) {
            float n=float(lut.get_width());
            float3 input=c;
            if(film!=0) {
                float3 l=srgb_decode(c);
                input=srgb_shape(float3(dot(l,float3(0.529388470712,0.330231217730,0.140627167763)),
                    dot(l,float3(0.098305088514,0.873483901238,0.028146056090)),
                    dot(l,float3(0.016851063808,0.117696843742,0.865673268566))));
            }
            g=lut.sample(linearSampler,(input*(n-1.0)+0.5)/n).rgb;
            float y=dot(c,float3(0.2126,0.7152,0.0722));
            float chroma=max(max(c.r,c.g),c.b)-min(min(c.r,c.g),c.b);
            float mask=smoothstep(0.78,0.96,y)*(1.0-smoothstep(0.04,0.20,chroma));
            if(film==0)g=mix(g,c,clamp(mask*0.90,0.0,1.0));
        }
        return float4(g,1);
    }
    """
}

struct RawIspLutPreview: UIViewRepresentable {
    let renderer: RawIspLutPreviewRenderer
    var active: Bool
    var developing: Bool
    let onFocus: (CGPoint, CGPoint) -> Void

    func makeCoordinator() -> PreviewFocusTap { PreviewFocusTap() }

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView(frame: .zero, device: renderer.device)
        view.delegate = renderer; view.framebufferOnly = false
        view.colorPixelFormat = .bgra8Unorm
        view.preferredFramesPerSecond = developing ? 15 : 30
        view.clearColor = MTLClearColorMake(0, 0, 0, 1)
        view.isPaused = !active
        context.coordinator.install(on: view)
        return view
    }
    func updateUIView(_ view: MTKView, context: Context) {
        // Keep the viewfinder live while giving the RAW and film passes more GPU time.
        let framesPerSecond = developing ? 15 : 30
        if view.preferredFramesPerSecond != framesPerSecond { view.preferredFramesPerSecond = framesPerSecond }
        view.isPaused = !active
        context.coordinator.action = { point, bounds in
            guard active, let target = renderer.focusPoint(at: point, in: bounds) else { return }
            onFocus(point, target)
        }
    }
    static func dismantleUIView(_ view: MTKView, coordinator: PreviewFocusTap) {
        view.isPaused = true
        view.delegate = nil
    }
}
