import { Body, Controller, Get, HttpCode, Param, Post, Request, UseGuards } from "@nestjs/common";
import { ApiBearerAuth, ApiTags } from "@nestjs/swagger";
import { Public } from "../common/decorators";
import { RolesGuard } from "../common/guards";
import { JobCreateDto } from "./dto";
import { IJobFeeRangeDto } from "./interfaces/feeRange";
import { JobEntity } from "./job.entity";
import { JobService } from "./job.service";
import { IJobDto, IManifestDataItemDto, IManifestDto } from "./serializers/job.responses";

@ApiBearerAuth()
@ApiTags("Job")
@Controller("/job")
export class JobController {
  constructor(private readonly jobService: JobService) {}

  @UseGuards(RolesGuard)
  @Get("/")
  @HttpCode(200)
  public getList(@Request() req: any): Promise<JobEntity[]> {
    return this.jobService.getJobByUser(req.user?.id);
  }

  @Get("/:id")
  @HttpCode(200)
  public getById(@Param("id") id: number): Promise<IJobDto> {
    return this.jobService.getJobById(id);
  }

  @Get("/:id/manifest")
  @Public()
  public async getManifest(@Param("id") id: number): Promise<IManifestDto> {
    return this.jobService.getManifest(id);
  }

  @Get("/:id/data-sample")
  @Public()
  public async getDataSample(@Param("id") id: number): Promise<IManifestDataItemDto[]> {
    return this.jobService.getDataSample(id);
  }

  @UseGuards(RolesGuard)
  @Post("/")
  public async create(@Request() req: any, @Body() data: JobCreateDto): Promise<IJobDto> {
    return this.jobService.create(data, req.user?.id);
  }

  @UseGuards(RolesGuard)
  @Post("/approve/:id")
  public async approve(@Param("id") id: number): Promise<boolean> {
    return this.jobService.approve(id);
  }

  @UseGuards(RolesGuard)
  @Post("/fee-suggestion")
  public async feeSuggestion(): Promise<IJobFeeRangeDto> {
    return this.jobService.getFeeRange();
  }
}
